package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.clean.CapacityBudget;
import com.atian10.logrecord.core.clean.CapacityCoordinator;
import com.atian10.logrecord.core.clean.CleanResult;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;

import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JDBC（桌面/服务器）全库容量协调器
 * <p>
 * 一个数据库只创建一个实例（由平台初始化层装配）。执行流程（修复方案 B4）：
 * </p>
 * <ol>
 *   <li>非阻塞获取两表导出保护写锁（顺序固定 log → exception）：导出快照复制期间
 *       返回 POSTPONED 推迟维护，避免先删除后才发现无法回收</li>
 *   <li>度量数据库相关文件总长；先执行 WAL checkpoint(TRUNCATE) 回收可回收空间，
 *       再决定是否删除数据</li>
 *   <li>读取实际 auto_vacuum 模式：INCREMENTAL 时执行 incremental_vacuum；
 *       空闲页足以覆盖超限量时执行 VACUUM（磁盘可用空间允许时）</li>
 *   <li>按 (timestamp, 固定表顺序 log→exception, id) 删除允许参与表中最旧的一批
 *       （每批有上限），提交后重新度量</li>
 *   <li>达标即停止；无可删数据、空间不足或工作预算耗尽时停止并报告 NOT_MET</li>
 * </ol>
 * <p>锁序与存储一致：exportGuard → helper（JdbcHelper 内部 monitor），
 * 任何持 helper 锁的路径不得再等待导出保护。</p>
 */
public final class JdbcCapacityCoordinator implements CapacityCoordinator {

    /** 每批删除的全局上限（跨参与表合计） */
    private static final int DELETE_BATCH = 500;
    /** 删除-回收循环的迭代上限（工作预算），防止无进展时持续误删 */
    private static final int MAX_ITERATIONS = 200;

    private final JdbcHelper helper;
    private final JdbcStorage logStorage;
    private final JdbcExceptionStorage exceptionStorage;

    /**
     * 构造容量协调器
     *
     * @param helper 数据库连接助手（与两存储共享同一实例）
     * @param logStorage 日志存储
     * @param exceptionStorage 异常存储
     */
    public JdbcCapacityCoordinator(JdbcHelper helper, JdbcStorage logStorage,
                                   JdbcExceptionStorage exceptionStorage) {
        if (helper == null) {
            throw new NullPointerException("helper == null");
        }
        if (logStorage == null) {
            throw new NullPointerException("logStorage == null");
        }
        if (exceptionStorage == null) {
            throw new NullPointerException("exceptionStorage == null");
        }
        this.helper = helper;
        this.logStorage = logStorage;
        this.exceptionStorage = exceptionStorage;
    }

    @Override
    public CleanResult enforceCapacity(CapacityBudget budget) {
        final long target = budget.getBudgetBytes();
        // 1. 非阻塞获取导出保护写锁（顺序固定 log → exception）
        ReentrantReadWriteLock.WriteLock logLock = logStorage.exportGuard().writeLock();
        ReentrantReadWriteLock.WriteLock expLock = exceptionStorage.exportGuard().writeLock();
        if (!logLock.tryLock()) {
            return result(0, 0, 0, 0, target, CleanResult.Outcome.POSTPONED,
                    "log table snapshot copying in progress");
        }
        if (!expLock.tryLock()) {
            logLock.unlock();
            return result(0, 0, 0, 0, target, CleanResult.Outcome.POSTPONED,
                    "exception table snapshot copying in progress");
        }
        long deletedLogs = 0;
        long deletedExceptions = 0;
        try {
            long before = helper.getDbTotalSizeBytes();
            try {
                long current = before;
                boolean vacuumAttempted = false;
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    // 每轮度量前先合并并截断 WAL（VACUUM/删除产生的页会先落在 -wal，
                    // 不 checkpoint 则总长不反映真实可回收状态）
                    checkpointTruncate();
                    current = helper.getDbTotalSizeBytes();
                    if (current <= target) {
                        return result((int) deletedLogs, (int) deletedExceptions,
                                before, current, target, CleanResult.Outcome.MET, null);
                    }
                    // 3. 读取实际 auto_vacuum 与空闲页，优先无删除回收
                    int autoVacuum = (int) helper.queryLong("PRAGMA auto_vacuum", null);
                    long pageSize = helper.queryLong("PRAGMA page_size", null);
                    long freePages = helper.queryLong("PRAGMA freelist_count", null);
                    long freeBytes = freePages * pageSize;
                    long excess = current - target;
                    if (autoVacuum == 2) {
                        // INCREMENTAL：提交增量回收后重新度量
                        helper.executeUpdate("PRAGMA incremental_vacuum", null);
                        continue;
                    }
                    if (freeBytes >= excess) {
                        // 空闲页足以覆盖超限量：VACUUM 回收（需磁盘空间允许，约需 2 倍）
                        File dbFile = new File(helper.getDbPath());
                        long usable = dbFile.getParentFile() != null
                                ? dbFile.getParentFile().getUsableSpace()
                                : dbFile.getUsableSpace();
                        if (usable < current) {
                            return result((int) deletedLogs, (int) deletedExceptions,
                                    before, current, target, CleanResult.Outcome.NOT_MET,
                                    "insufficient disk space for VACUUM (usable=" + usable
                                            + ", need≈" + current + ")");
                        }
                        helper.executeUpdate("VACUUM", null);
                        vacuumAttempted = true;
                        continue;
                    }
                    // 4. 删除参与表中最旧一批
                    long[] deleted = deleteOldestBatch(budget);
                    if (deleted[0] + deleted[1] == 0L) {
                        // 无可删数据：删除产生的空闲页可能尚未实际回收，最终 VACUUM 一次
                        if (!vacuumAttempted) {
                            helper.executeUpdate("VACUUM", null);
                            vacuumAttempted = true;
                            continue;
                        }
                        return result((int) deletedLogs, (int) deletedExceptions,
                                before, current, target, CleanResult.Outcome.NOT_MET,
                                "no deletable data within eligible tables");
                    }
                    deletedLogs += deleted[0];
                    deletedExceptions += deleted[1];
                }
                return result((int) deletedLogs, (int) deletedExceptions,
                        before, current, target, CleanResult.Outcome.NOT_MET,
                        "work budget exhausted");
            } catch (SQLException e) {
                long after = helper.getDbTotalSizeBytes();
                return result((int) deletedLogs, (int) deletedExceptions,
                        before, after, target, CleanResult.Outcome.FAILED,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            expLock.unlock();
            logLock.unlock();
        }
    }

    /**
     * 执行 WAL checkpoint(TRUNCATE)，尽力把 WAL 内容合并回主文件
     */
    private void checkpointTruncate() throws SQLException {
        try {
            helper.query("PRAGMA wal_checkpoint(TRUNCATE)", null, ResultSet::next);
        } catch (SQLException e) {
            // checkpoint 失败不终止维护：按主文件继续度量与删除
        }
    }

    /**
     * 删除参与表中最旧的一批（全局 (timestamp, 固定表顺序 log→exception, id) 次序）
     *
     * @return [日志删除数, 异常删除数]
     */
    private long[] deleteOldestBatch(CapacityBudget budget) throws SQLException {
        // 全局第 N 小时间戳作为本批上界（UNION ALL LIMIT 取第 DELETE_BATCH 个）
        StringBuilder union = new StringBuilder(96);
        List<Object> noArgs = new ArrayList<>();
        boolean any = false;
        if (budget.isLogsEligible()) {
            union.append("SELECT timestamp FROM log_record");
            any = true;
        }
        if (budget.isExceptionsEligible()) {
            if (any) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT timestamp FROM exception_table");
            any = true;
        }
        if (!any) {
            return new long[]{0L, 0L};
        }
        Long threshold = helper.query(
                "SELECT timestamp FROM (" + union + ") ORDER BY timestamp LIMIT 1 OFFSET "
                        + (DELETE_BATCH - 1),
                noArgs.toArray(), rs -> rs.next() ? Long.valueOf(rs.getLong(1)) : null);
        if (threshold == null) {
            // 参与表数据量不足一批：上界取全局最大时间戳（本批可清完全部参与数据）
            threshold = helper.query(
                    "SELECT MAX(timestamp) FROM (" + union + ")",
                    noArgs.toArray(), rs -> rs.next() ? Long.valueOf(rs.getLong(1)) : null);
            if (threshold == null) {
                return new long[]{0L, 0L};
            }
        }
        long deletedLogs = 0L;
        long deletedExceptions = 0L;
        int remaining = DELETE_BATCH;
        if (budget.isLogsEligible() && remaining > 0) {
            int d = helper.executeUpdate(
                    "DELETE FROM log_record WHERE id IN ("
                            + "SELECT id FROM log_record WHERE timestamp <= ? "
                            + "ORDER BY timestamp ASC, id ASC LIMIT ?)",
                    new Object[]{threshold, remaining});
            deletedLogs = d;
            remaining -= d;
        }
        if (budget.isExceptionsEligible() && remaining > 0) {
            int d = helper.executeUpdate(
                    "DELETE FROM exception_table WHERE id IN ("
                            + "SELECT id FROM exception_table WHERE timestamp <= ? "
                            + "ORDER BY timestamp ASC, id ASC LIMIT ?)",
                    new Object[]{threshold, remaining});
            deletedExceptions = d;
        }
        return new long[]{deletedLogs, deletedExceptions};
    }

    /**
     * 构造清理结果
     */
    private static CleanResult result(int deletedLogs, int deletedExceptions,
                                      long bytesBefore, long bytesAfter, long targetBytes,
                                      CleanResult.Outcome outcome, String stopReason) {
        return new CleanResult(deletedLogs, deletedExceptions, bytesBefore, bytesAfter,
                targetBytes, outcome, stopReason);
    }
}
