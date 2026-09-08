package com.atian10.logrecord.android;

import com.atian10.logrecord.core.clean.CapacityBudget;
import com.atian10.logrecord.core.clean.CapacityCoordinator;
import com.atian10.logrecord.core.clean.CleanResult;
import com.atian10.logrecord.android.room.LogDatabase;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Room（Android）全库容量协调器
 * <p>
 * 语义与桌面端 JdbcCapacityCoordinator 相同（修复方案 B4）：导出快照复制期间
 * POSTPONED 推迟；先 checkpoint 回收 WAL，再按实际 auto_vacuum 决定
 * incremental_vacuum / VACUUM / 删除最旧一批；达标即停，未达标如实报告。
 * 数据库操作经 SupportSQLiteDatabase 执行。
 * </p>
 * <p>
 * <b>验证边界</b>：本类行为需 Android 仪器测试验证（真实 Room 线程限制与
 * auto_vacuum 实测），当前仅通过编译验证。
 * </p>
 */
public final class RoomCapacityCoordinator implements CapacityCoordinator {

    /** 每批删除的全局上限（跨参与表合计） */
    private static final int DELETE_BATCH = 500;
    /** 删除-回收循环的迭代上限（工作预算），防止无进展时持续误删 */
    private static final int MAX_ITERATIONS = 200;

    private final LogDatabase database;
    private final RoomStorage logStorage;
    private final RoomExceptionStorage exceptionStorage;

    /**
     * 构造容量协调器
     *
     * @param database 日志数据库（与两存储共享同一实例）
     * @param logStorage 日志存储
     * @param exceptionStorage 异常存储
     */
    public RoomCapacityCoordinator(LogDatabase database, RoomStorage logStorage,
                                   RoomExceptionStorage exceptionStorage) {
        if (database == null) {
            throw new NullPointerException("database == null");
        }
        if (logStorage == null) {
            throw new NullPointerException("logStorage == null");
        }
        if (exceptionStorage == null) {
            throw new NullPointerException("exceptionStorage == null");
        }
        this.database = database;
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
            long before = measureTotalSize();
            try {
                SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
                long current = before;
                boolean vacuumAttempted = false;
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    // 每轮度量前先合并并截断 WAL（VACUUM/删除产生的页会先落在 -wal，
                    // 不 checkpoint 则总长不反映真实可回收状态）
                    execIgnoreErrors(db, "PRAGMA wal_checkpoint(TRUNCATE)");
                    current = measureTotalSize();
                    if (current <= target) {
                        return result((int) deletedLogs, (int) deletedExceptions,
                                before, current, target, CleanResult.Outcome.MET, null);
                    }
                    // 3. 读取实际 auto_vacuum 与空闲页，优先无删除回收
                    long autoVacuum = queryLong(db, "PRAGMA auto_vacuum");
                    long pageSize = queryLong(db, "PRAGMA page_size");
                    long freePages = queryLong(db, "PRAGMA freelist_count");
                    long freeBytes = freePages * pageSize;
                    long excess = current - target;
                    if (autoVacuum == 2L) {
                        execIgnoreErrors(db, "PRAGMA incremental_vacuum");
                        continue;
                    }
                    if (freeBytes >= excess) {
                        File dbFile = getDatabaseFile();
                        long usable = dbFile != null && dbFile.getParentFile() != null
                                ? dbFile.getParentFile().getUsableSpace() : 0L;
                        if (usable < current) {
                            return result((int) deletedLogs, (int) deletedExceptions,
                                    before, current, target, CleanResult.Outcome.NOT_MET,
                                    "insufficient disk space for VACUUM (usable=" + usable
                                            + ", need≈" + current + ")");
                        }
                        db.execSQL("VACUUM");
                        vacuumAttempted = true;
                        continue;
                    }
                    // 4. 删除参与表中最旧一批
                    long[] deleted = deleteOldestBatch(budget);
                    if (deleted[0] + deleted[1] == 0L) {
                        // 无可删数据：删除产生的空闲页可能尚未实际回收，最终 VACUUM 一次
                        if (!vacuumAttempted) {
                            db.execSQL("VACUUM");
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
            } catch (Throwable t) {
                long after = measureTotalSize();
                return result((int) deletedLogs, (int) deletedExceptions,
                        before, after, target, CleanResult.Outcome.FAILED,
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } finally {
            expLock.unlock();
            logLock.unlock();
        }
    }

    /**
     * 度量数据库相关文件总长：主文件 + -wal + -shm + -journal
     */
    private long measureTotalSize() {
        File dbFile = getDatabaseFile();
        if (dbFile == null) {
            return 0L;
        }
        String path = dbFile.getAbsolutePath();
        long total = 0L;
        String[] suffixes = {"", "-wal", "-shm", "-journal"};
        for (String suffix : suffixes) {
            File f = new File(path + suffix);
            if (f.exists()) {
                total += f.length();
            }
        }
        return total;
    }

    /**
     * 获取数据库主文件
     */
    private File getDatabaseFile() {
        try {
            String path = database.getOpenHelper().getWritableDatabase().getPath();
            return path == null ? null : new File(path);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取单值 PRAGMA（如 auto_vacuum/page_size/freelist_count）
     */
    private static long queryLong(SupportSQLiteDatabase db, String pragma) {
        Cursor cursor = null;
        try {
            cursor = db.query(pragma);
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
            return 0L;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * 执行可能失败的维护语句（失败不终止流程）
     */
    private static void execIgnoreErrors(SupportSQLiteDatabase db, String sql) {
        try {
            db.execSQL(sql);
        } catch (Throwable ignored) {
            // checkpoint 失败不终止维护
        }
    }

    /**
     * 删除参与表中最旧的一批（全局 (timestamp, 固定表顺序 log→exception, id) 次序）
     *
     * @return [日志删除数, 异常删除数]
     */
    private long[] deleteOldestBatch(CapacityBudget budget) {
        // 合并两表最旧的 DELETE_BATCH 条时间戳，取第 N 小为批次上界
        List<Long> merged = new ArrayList<>();
        if (budget.isLogsEligible()) {
            merged.addAll(logStorage.oldestTimestamps(DELETE_BATCH));
        }
        if (budget.isExceptionsEligible()) {
            merged.addAll(exceptionStorage.oldestTimestamps(DELETE_BATCH));
        }
        if (merged.isEmpty()) {
            return new long[]{0L, 0L};
        }
        Collections.sort(merged);
        long threshold = merged.size() >= DELETE_BATCH
                ? merged.get(DELETE_BATCH - 1)
                : merged.get(merged.size() - 1);
        long deletedLogs = 0L;
        long deletedExceptions = 0L;
        int remaining = DELETE_BATCH;
        if (budget.isLogsEligible() && remaining > 0) {
            int d = logStorage.deleteOldestUpTo(threshold, remaining);
            deletedLogs = d;
            remaining -= d;
        }
        if (budget.isExceptionsEligible() && remaining > 0) {
            deletedExceptions = exceptionStorage.deleteOldestUpTo(threshold, remaining);
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
