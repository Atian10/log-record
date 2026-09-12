package com.atian10.logrecord.android;

import com.atian10.logrecord.core.DatabaseOperationGuard;

import com.atian10.logrecord.core.clean.CapacityBudget;
import com.atian10.logrecord.core.clean.CapacityCoordinator;
import com.atian10.logrecord.core.clean.CleanResult;
import com.atian10.logrecord.android.room.LogDatabase;
import android.database.Cursor;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Room 全库容量维护，与 JDBC 共用排序、停止和跨轮恢复语义。
 * 当前修改只做静态复核；真实 Room 事务、设备及文件系统行为仍未验证。
 */
public final class RoomCapacityCoordinator implements CapacityCoordinator {
    /** 两表和数据库共用同一所有者及操作保护。 */
    private final LogDatabase database;
    private final RoomStorage logStorage;
    private final RoomExceptionStorage exceptionStorage;

    /** 装配同一 Room 数据库的两表。 */
    public RoomCapacityCoordinator(LogDatabase database, RoomStorage logStorage, RoomExceptionStorage exceptionStorage) {
        if (database == null || logStorage == null || exceptionStorage == null)
            throw new NullPointerException("capacity dependencies must not be null");
        this.database = database;
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
        try (DatabaseOperationGuard.Scope operation = database.getOperationGuard().enter()) {
            ReentrantReadWriteLock.WriteLock logLock = logStorage.exportGuard().writeLock();
            ReentrantReadWriteLock.WriteLock expLock = exceptionStorage.exportGuard().writeLock();
            if (!logLock.tryLock())
                return result(round, target, CleanResult.Outcome.POSTPONED, "log snapshot is active");
            try {
                if (!expLock.tryLock())
                    return result(round, target, CleanResult.Outcome.POSTPONED, "exception snapshot is active");
                try (DatabaseOperationGuard.Access maintenance = database.getOperationGuard().tryMaintenance()) {
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

    /** 取得受当前操作许可保护的实际数据库连接。 */
    private SupportSQLiteDatabase connection() {
        return database.getOpenHelper().getWritableDatabase();
    }

    /** 内存数据库及路径缺失不能提供物理文件预算。 */
    private File getDatabaseFile() throws IOException {
        String path = connection().getPath();
        if (path == null || path.isEmpty() || ":memory:".equals(path))
            throw new IOException("capacity requires a file database");
        return new File(path);
    }

    /** 读取必须存在且非空的 PRAGMA 单值。 */
    private long queryLong(String sql) throws IOException {
        try (Cursor cursor = connection().query(sql)) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) throw new IOException("missing PRAGMA result: " + sql);
            return cursor.getLong(0);
        }
    }

    /** 校验 checkpoint 三列；忙碌立即推迟，不能先删除后忽略回收失败。 */
    private void checkpointTruncate() throws Exception {
        try (Cursor cursor = connection().query("PRAGMA main.wal_checkpoint(TRUNCATE)")) {
            if (!cursor.moveToFirst() || cursor.getColumnCount() < 3)
                throw new IOException("missing checkpoint result");
            long busy = cursor.getLong(0);
            long logFrames = cursor.getLong(1);
            long checkpointed = cursor.getLong(2);
            if (busy != 0)
                throw new MaintenanceIssue(CleanResult.Outcome.POSTPONED, "checkpoint busy");
            if (!((logFrames == -1L && checkpointed == -1L)
                    || (logFrames >= 0L && logFrames == checkpointed)))
                throw new IOException("incomplete checkpoint");
        }
    }

    /** Cursor 首次计数执行全部步骤；不跨窗口移动游标，避免重复执行有副作用的 PRAGMA。 */
    private void executeMaintenance(String sql) {
        if (sql.startsWith("PRAGMA")) {
            try (Cursor cursor = connection().query(sql)) {
                cursor.getCount();
            }
        } else {
            connection().execSQL(sql);
        }
    }

    /** 候选选择和跨表删除在同一事务，endTransaction 成功后才确认删除计数。 */
    private void deleteOldestBatch(CapacityBudget budget, Round round) throws Exception {
        String sql = oldestSql(budget);
        if (sql == null) return;
        SupportSQLiteDatabase db = connection();
        if (db.inTransaction()) throw new IOException("nested capacity transaction is unsupported");
        int deletedLogs = 0;
        int deletedExceptions = 0;
        db.beginTransaction();
        try {
            List<Long> logs = new ArrayList<>();
            List<Long> exceptions = new ArrayList<>();
            try (Cursor cursor = db.query(sql)) {
                while (cursor.moveToNext()) {
                    (cursor.getInt(0) == 0 ? logs : exceptions).add(cursor.getLong(1));
                }
            }
            deletedLogs = deleteIds(db, "log_record", logs);
            deletedExceptions = deleteIds(db, "exception_table", exceptions);
            db.setTransactionSuccessful();
        } finally {
            // endTransaction 抛错时结果未知，外层进入仅恢复状态且不重放。
            db.endTransaction();
        }
        round.logs += deletedLogs;
        round.exceptions += deletedExceptions;
    }

    /** 每条语句最多绑定全局批次上限个 ID，保持在 SQLite 参数数量限制内。 */
    private static int deleteIds(SupportSQLiteDatabase db, String table, List<Long> ids) throws Exception {
        if (ids.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("DELETE FROM " + table + " WHERE id IN (");
        for (int index = 0; index < ids.size(); index++) sql.append(index == 0 ? "?" : ",?");
        sql.append(')');
        try (SupportSQLiteStatement statement = db.compileStatement(sql.toString())) {
            for (int index = 0; index < ids.size(); index++) statement.bindLong(index + 1, ids.get(index));
            return statement.executeUpdateDelete();
        }
    }
}
