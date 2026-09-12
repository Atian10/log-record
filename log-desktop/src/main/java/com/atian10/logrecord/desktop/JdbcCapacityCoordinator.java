package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.DatabaseOperationGuard;

import com.atian10.logrecord.core.clean.CapacityBudget;
import com.atian10.logrecord.core.clean.CapacityCoordinator;
import com.atian10.logrecord.core.clean.CleanResult;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JDBC 全库容量维护。每批按全库顺序删除具体 ID，提交及回收都确认后才继续。
 * 失败后的实例进入仅恢复状态；该状态不跨进程持久化，也不构成数据库硬配额。
 */
public final class JdbcCapacityCoordinator implements CapacityCoordinator {
    /** 两表和连接共用同一所有者及操作保护。 */
    private final JdbcHelper helper;
    private final JdbcStorage logStorage;
    private final JdbcExceptionStorage exceptionStorage;

    /** 装配同一数据库的两表；调用方不得用不同连接的存储混装。 */
    public JdbcCapacityCoordinator(JdbcHelper helper, JdbcStorage logStorage, JdbcExceptionStorage exceptionStorage) {
        if (helper == null || logStorage == null || exceptionStorage == null)
            throw new NullPointerException("capacity dependencies must not be null");
        this.helper = helper;
        this.logStorage = logStorage;
        this.exceptionStorage = exceptionStorage;
    }

    /** 全库单批及单轮删除上限；无进展会提前停止。 */
    private static final int DELETE_BATCH = 500;
    private static final int MAX_ITERATIONS = 200;
    /** 维护独占锁保护的跨轮状态；实例重建后不保留。 */
    private boolean recoveryOnly;
    private boolean requireSizeProgress;
    private long blockedBytes = -1L;

    /** 单轮已确认提交的删除量与最近一次可靠度量，-1 表示尚无度量。 */
    private static final class Round {
        int logs;
        int exceptions;
        long before = -1L;
        long current = -1L;
    }

    /** 可诊断的停止原因；仅 checkpoint 忙碌属于推迟。 */
    private static final class MaintenanceIssue extends Exception {
        final CleanResult.Outcome outcome;
        MaintenanceIssue(CleanResult.Outcome outcome, String message) {
            super(message);
            this.outcome = outcome;
        }
    }

    /** 固定锁序：存活许可 → 日志导出写锁 → 异常导出写锁 → 维护独占锁。 */
    @Override
    public CleanResult enforceCapacity(CapacityBudget budget) {
        if (budget == null) throw new NullPointerException("budget == null");
        final Round round = new Round();
        final long target = budget.getBudgetBytes();
        try (DatabaseOperationGuard.Scope operation = helper.getOperationGuard().enter()) {
            ReentrantReadWriteLock.WriteLock logLock = logStorage.exportGuard().writeLock();
            ReentrantReadWriteLock.WriteLock expLock = exceptionStorage.exportGuard().writeLock();
            if (!logLock.tryLock())
                return result(round, target, CleanResult.Outcome.POSTPONED, "log snapshot is active");
            try {
                if (!expLock.tryLock())
                    return result(round, target, CleanResult.Outcome.POSTPONED, "exception snapshot is active");
                try (DatabaseOperationGuard.Access maintenance = helper.getOperationGuard().tryMaintenance()) {
                    if (maintenance == null)
                        return result(round, target, CleanResult.Outcome.POSTPONED, "database operation is active");
                    return enforceLocked(budget, round);
                } finally {
                    expLock.unlock();
                }
            } finally {
                logLock.unlock();
            }
        } catch (Exception error) {
            return result(round, target, CleanResult.Outcome.FAILED, error.toString());
        }
    }

    /** 先无删除回收；恢复轮次绝不落入删除分支，失败及无进展均封闭后续删除。 */
    private CleanResult enforceLocked(CapacityBudget budget, Round round) {
        final long target = budget.getBudgetBytes();
        final boolean recovering = recoveryOnly;
        try {
            round.before = measureTotalSize();
            round.current = round.before;
            int mode = (int) queryLong("PRAGMA auto_vacuum");
            if (mode < 0 || mode > 2) throw new IOException("invalid auto_vacuum mode");
            reclaim(mode);
            round.current = measureTotalSize();
            if (round.current <= target) {
                recoveryOnly = false;
                requireSizeProgress = false;
                return result(round, target, CleanResult.Outcome.MET, null);
            }
            // 对可能使用 VACUUM 的模式，删除前必须证明有足够额外空间。
            if (mode != 2) ensureVacuumSpace();
            if (recovering) {
                boolean recovered = !requireSizeProgress || round.current < blockedBytes;
                if (recovered) {
                    recoveryOnly = false;
                    requireSizeProgress = false;
                }
                return result(round, target, CleanResult.Outcome.NOT_MET,
                        recovered ? "recovery checks passed; deletion deferred to next round"
                                  : "recovery only: no physical size progress");
            }
            for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                // 记录本批前的体积，只有物理文件确实缩小时才能继续下一批。
                long previousBytes = round.current;
                int previousDeletes = round.logs + round.exceptions;
                if (mode != 2) ensureVacuumSpace();
                deleteOldestBatch(budget, round);
                if (round.logs + round.exceptions == previousDeletes)
                    return result(round, target, CleanResult.Outcome.NOT_MET, "no deletable data within eligible tables");
                reclaim(mode);
                round.current = measureTotalSize();
                if (round.current <= target)
                    return result(round, target, CleanResult.Outcome.MET, null);
                if (round.current >= previousBytes) {
                    recoveryOnly = true;
                    requireSizeProgress = true;
                    blockedBytes = round.current;
                    return result(round, target, CleanResult.Outcome.NOT_MET,
                            "no physical size progress; recovery only");
                }
            }
            return result(round, target, CleanResult.Outcome.NOT_MET, "work budget exhausted");
        } catch (Exception error) {
            recoveryOnly = true;
            // 保留已有的无进展门槛，临时错误不能让下轮绕过它。
            if (!requireSizeProgress) blockedBytes = round.current;
            round.current = -1L;
            CleanResult.Outcome outcome = error instanceof MaintenanceIssue
                    ? ((MaintenanceIssue) error).outcome : CleanResult.Outcome.FAILED;
            return result(round, target, outcome, "recovery only: " + error.toString());
        }
    }

    /** 确认 checkpoint 后才回收；freePages=0 时继续判定是否需要删除，不空转。 */
    private void reclaim(int mode) throws Exception {
        checkpointTruncate();
        long freePages = queryLong("PRAGMA freelist_count");
        if (freePages < 0) throw new IOException("invalid freelist_count");
        if (freePages > 0) {
            if (mode == 2) {
                executeMaintenance("PRAGMA incremental_vacuum(" + freePages + ")");
            } else {
                ensureVacuumSpace();
                executeMaintenance("VACUUM");
            }
        }
        checkpointTruncate();
    }

    /** VACUUM 最多需要约两倍主文件的额外空间；未知空间按不可回收处理。 */
    private void ensureVacuumSpace() throws Exception {
        File dbFile = getDatabaseFile();
        long mainBytes = checkedFileSize(dbFile, true);
        File parent = dbFile.getAbsoluteFile().getParentFile();
        long usable = parent == null ? 0L : parent.getUsableSpace();
        if (mainBytes > Long.MAX_VALUE / 2 || usable < mainBytes * 2)
            throw new MaintenanceIssue(CleanResult.Outcome.NOT_MET, "insufficient disk space for VACUUM");
    }

    /** 只接受可读取的实际文件；不把缺失、内存库或加总溢出伪装成零字节。 */
    private long measureTotalSize() throws Exception {
        File dbFile = getDatabaseFile();
        long total = checkedFileSize(dbFile, true);
        String[] suffixes = {"-wal", "-shm", "-journal"};
        for (String suffix : suffixes) {
            long length = checkedFileSize(new File(dbFile.getAbsolutePath() + suffix), false);
            if (length > Long.MAX_VALUE - total) throw new IOException("database size overflow");
            total += length;
        }
        return total;
    }

    /** 主文件必须存在且非空；可选边文件不存在时计零，存在却不可读时失败。 */
    private static long checkedFileSize(File file, boolean required) throws IOException {
        if (!file.exists() && !required) return 0L;
        if (!file.isFile() || !file.canRead()) throw new IOException("unreadable database file: " + file);
        long length = file.length();
        if (required && length <= 0L) throw new IOException("empty database file: " + file);
        return length;
    }

    /** 固定表顺序解决时间戳并列；只从允许参与的表选具体 ID。 */
    private static String oldestSql(CapacityBudget budget) {
        String union = budget.isLogsEligible()
                ? "SELECT 0 AS table_order, id, timestamp FROM log_record" : "";
        if (budget.isExceptionsEligible()) {
            if (!union.isEmpty()) union += " UNION ALL ";
            union += "SELECT 1 AS table_order, id, timestamp FROM exception_table";
        }
        return union.isEmpty() ? null : "SELECT table_order, id FROM (" + union
                + ") ORDER BY timestamp ASC, table_order ASC, id ASC LIMIT " + DELETE_BATCH;
    }

    /** 只报告已确认提交的计数；文件度量未知使用 -1。 */
    private static CleanResult result(Round round, long target, CleanResult.Outcome outcome, String reason) {
        return new CleanResult(round.logs, round.exceptions, round.before, round.current, target, outcome, reason);
    }

    /** 读取实际路径；内存库没有可度量的物理预算。 */
    private File getDatabaseFile() throws IOException {
        String path = helper.getDbPath();
        if (path == null || path.isEmpty() || ":memory:".equals(path))
            throw new IOException("capacity requires a file database");
        return new File(path);
    }

    /** 读取必须存在的 PRAGMA 单值，不以缺失行替代零。 */
    private long queryLong(String sql) throws SQLException {
        return helper.query(sql, null, rows -> {
            if (!rows.next() || rows.getObject(1) == null) throw new SQLException("missing PRAGMA result: " + sql);
            return rows.getLong(1);
        });
    }

    /** 检查 busy/log/checkpointed 三列；非 WAL 模式允许 SQLite 的 -1/-1 返回值。 */
    private void checkpointTruncate() throws Exception {
        long[] values = helper.query("PRAGMA main.wal_checkpoint(TRUNCATE)", null, rows -> {
            if (!rows.next() || rows.getMetaData().getColumnCount() < 3)
                throw new SQLException("missing checkpoint result");
            return new long[]{rows.getLong(1), rows.getLong(2), rows.getLong(3)};
        });
        if (values[0] != 0)
            throw new MaintenanceIssue(CleanResult.Outcome.POSTPONED, "checkpoint busy");
        if (!((values[1] == -1L && values[2] == -1L)
                || (values[1] >= 0L && values[1] == values[2])))
            throw new SQLException("incomplete checkpoint");
    }

    /** Xerial 普通 Statement.executeUpdate 走 sqlite3_exec，消费零列 PRAGMA 的全部步骤。 */
    private void executeMaintenance(String sql) throws SQLException {
        synchronized (helper) {
            // 不能换成 PreparedStatement.executeQuery/executeUpdate：增量回收为零列多步语句。
            try (java.sql.Statement statement = helper.getConnection().createStatement()) {
                statement.executeUpdate(sql);
            }
        }
    }

    /** 选 ID 与两表删除在同一事务；commit 结果未知时关闭连接且绝不重放。 */
    private void deleteOldestBatch(CapacityBudget budget, Round round) throws SQLException {
        String sql = oldestSql(budget);
        if (sql == null) return;
        synchronized (helper) {
            Connection connection = helper.getConnection();
            if (!connection.getAutoCommit()) throw new SQLException("capacity requires autoCommit");
            // 仅确认回滚或提交后才能恢复自动提交，避免恢复动作提交未知事务。
            boolean commitStarted = false;
            boolean committed = false;
            boolean rolledBack = false;
            boolean restored = false;
            SQLException failure = null;
            try {
                connection.setAutoCommit(false);
                List<Long> logs = new ArrayList<>();
                List<Long> exceptions = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        (rows.getInt(1) == 0 ? logs : exceptions).add(rows.getLong(2));
                    }
                }
                int deletedLogs = deleteIds(connection, "log_record", logs);
                int deletedExceptions = deleteIds(connection, "exception_table", exceptions);
                commitStarted = true;
                connection.commit();
                committed = true;
                round.logs += deletedLogs;
                round.exceptions += deletedExceptions;
            } catch (SQLException error) {
                failure = error;
                if (!commitStarted) {
                    try { connection.rollback(); rolledBack = true; }
                    catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                }
            } finally {
                if (committed || rolledBack) {
                    try { connection.setAutoCommit(true); restored = true; }
                    catch (SQLException restoreError) {
                        if (failure == null) failure = restoreError;
                        else failure.addSuppressed(restoreError);
                    }
                }
                if (!restored) {
                    try { connection.close(); }
                    catch (SQLException closeError) {
                        if (failure == null) failure = closeError;
                        else failure.addSuppressed(closeError);
                    }
                }
            }
            if (failure != null) throw failure;
        }
    }

    /** 绑定选中的 ID（最多 500 个），不再通过时间戳阈值扩大候选集合。 */
    private static int deleteIds(Connection connection, String table, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("DELETE FROM " + table + " WHERE id IN (");
        for (int index = 0; index < ids.size(); index++) sql.append(index == 0 ? "?" : ",?");
        sql.append(')');
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < ids.size(); index++) statement.setLong(index + 1, ids.get(index));
            return statement.executeUpdate();
        }
    }
}
