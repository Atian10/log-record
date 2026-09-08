package com.atian10.logrecord.android;

import androidx.sqlite.db.SimpleSQLiteQuery;

import android.os.Looper;
import android.util.Log;

import com.atian10.logrecord.core.ExportSnapshotException;
import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.IExportSnapshot;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.core.util.LikeEscapeUtil;
import com.atian10.logrecord.android.room.ExceptionDao;
import com.atian10.logrecord.android.room.ExceptionEntity;
import com.atian10.logrecord.android.room.ExceptionEntityConverter;
import com.atian10.logrecord.android.room.LogDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Room 实现的异常存储
 * <p>
 * 实现 {@link IExceptionStorage}，将异常持久化到独立的 exception_table 表。
 * 与日志表完全独立，无外键关联。
 * </p>
 */
public final class RoomExceptionStorage implements IExceptionStorage {

    private final LogDatabase database;
    private final ExceptionDao dao;

    /**
     * 导出读取保护：快照复制持读锁，清理持写锁互斥
     * <p>锁序固定为 exportGuard → dao/Room 内部锁，
     * 任何持 Room 锁的路径不得再等待本锁，避免死锁</p>
     */
    private final ReentrantReadWriteLock exportGuard = new ReentrantReadWriteLock();

    /**
     * 构造 RoomExceptionStorage
     * @param database 已构建的 LogDatabase
     */
    public RoomExceptionStorage(LogDatabase database) {
        if (database == null) {
            throw new NullPointerException("database == null");
        }
        this.database = database;
        this.dao = database.exceptionDao();
    }

    @Override
    public void write(ExceptionRecord record) {
        ExceptionEntity entity = ExceptionEntityConverter.toEntity(record);
        if (entity != null) {
            dao.insert(entity);
        }
    }

    @Override
    public void writeBatch(List<ExceptionRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<ExceptionEntity> entities = new ArrayList<>(records.size());
        for (ExceptionRecord r : records) {
            ExceptionEntity e = ExceptionEntityConverter.toEntity(r);
            if (e != null) {
                entities.add(e);
            }
        }
        if (!entities.isEmpty()) {
            dao.insertAll(entities);
        }
    }

    @Override
    public List<ExceptionRecord> query(ExceptionQuery query) {
        warnIfMainThread("queryExceptions");
        if (query == null) {
            return new ArrayList<>();
        }
        List<Object> args = new ArrayList<>();
        String sql = buildQuerySql(query, args, false);
        List<ExceptionEntity> entities = dao.query(new SimpleSQLiteQuery(sql, args.toArray()));
        List<ExceptionRecord> result = new ArrayList<>();
        if (entities != null) {
            for (ExceptionEntity e : entities) {
                ExceptionRecord r = ExceptionEntityConverter.toRecord(e);
                if (r != null) {
                    result.add(r);
                }
            }
        }
        return result;
    }

    @Override
    public int clean(CleanPolicy policy) {
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → dao）
        exportGuard.writeLock().lock();
        try {
            if (policy == null || !policy.isEnabled()) {
                return 0;
            }
            int cleaned = 0;
            // 按天数清理（表级规则；容量删除已上移到全库容量协调器，B4）
            if (policy.getKeepDays() > 0) {
                long threshold = System.currentTimeMillis()
                        - (long) policy.getKeepDays() * 24L * 60L * 60L * 1000L;
                cleaned += dao.cleanBefore(threshold);
            }
            // 按数量清理
            if (policy.getMaxRecordCount() > 0 && dao.countAll() > policy.getMaxRecordCount()) {
                cleaned += dao.cleanByCount((int) policy.getMaxRecordCount());
            }
            return cleaned;
        } finally {
            exportGuard.writeLock().unlock();
        }
    }

    @Override
    public int cleanBefore(long timestamp) {
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → dao）
        exportGuard.writeLock().lock();
        try {
            return dao.cleanBefore(timestamp);
        } finally {
            exportGuard.writeLock().unlock();
        }
    }

    @Override
    public int cleanByCount(int keepCount) {
        if (keepCount < 0) {
            keepCount = 0;
        }
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → dao）
        exportGuard.writeLock().lock();
        try {
            return dao.cleanByCount(keepCount);
        } finally {
            exportGuard.writeLock().unlock();
        }
    }

    @Override
    public long getRecordCount() {
        return dao.countAll();
    }

    @Override
    public long getDbSizeBytes() {
        try {
            File f = database.getOpenHelper().getReadableDatabase().getPath() == null
                    ? null : new File(database.getOpenHelper().getReadableDatabase().getPath());
            if (f != null && f.exists()) {
                return f.length();
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    @Override
    public long count(ExceptionQuery query) {
        warnIfMainThread("countExceptions");
        if (query == null) {
            return dao.countAll();
        }
        List<Object> args = new ArrayList<>();
        String sql = buildQuerySql(query, args, true);
        return dao.count(new SimpleSQLiteQuery(sql, args.toArray()));
    }

    /**
     * 构建异常查询 SQL
     */
    private String buildQuerySql(ExceptionQuery query, List<Object> args, boolean countMode) {
        StringBuilder sql = new StringBuilder(128);
        if (countMode) {
            sql.append("SELECT COUNT(*) FROM exception_table");
        } else {
            sql.append("SELECT * FROM exception_table");
        }
        boolean hasWhere = appendWhereClause(sql, args, query);
        if (!countMode) {
            sql.append(" ORDER BY timestamp ");
            sql.append(query.getOrderBy() == OrderBy.ASC ? "ASC" : "DESC");
            if (query.getLimit() > 0) {
                sql.append(" LIMIT ? OFFSET ?");
                args.add(query.getLimit());
                args.add(query.getOffset());
            }
        }
        return sql.toString();
    }

    /**
     * 追加 WHERE 子句到 SQL 构造器
     * <p>抽取公共 WHERE 拼接逻辑，供 buildQuerySql 与导出快照复用</p>
     * @param sql SQL 构造器
     * @param args 参数列表（输出）
     * @param query 查询条件
     * @return 是否已追加过条件（用于调用方决定后续 AND/WHERE 前缀）
     */
    private boolean appendWhereClause(StringBuilder sql, List<Object> args, ExceptionQuery query) {
        boolean hasWhere = false;
        if (query.getTag() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("log_tag = ?");
            args.add(query.getTag());
            hasWhere = true;
        }
        if (query.getExceptionClass() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("exception_class = ?");
            args.add(query.getExceptionClass());
            hasWhere = true;
        }
        if (query.getKeyword() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("exception_msg LIKE ? ESCAPE '\\'");
            args.add(LikeEscapeUtil.contains(query.getKeyword()));
            hasWhere = true;
        }
        if (query.getFromTime() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("timestamp >= ?");
            args.add(query.getFromTime());
            hasWhere = true;
        }
        if (query.getToTime() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("timestamp <= ?");
            args.add(query.getToTime());
            hasWhere = true;
        }
        return hasWhere;
    }

    // ===== 导出快照 =====

    /**
     * 导出读取保护锁（包内可见，供全库容量协调器与导出复制互斥）
     */
    ReentrantReadWriteLock exportGuard() {
        return exportGuard;
    }

    /**
     * 删除时间戳不晚于阈值的最旧一批记录（全库容量协调使用；调用方须持有导出保护写锁）
     * @param ts 时间戳上界（包含）
     * @param limit 本批上限
     * @return 删除的记录数
     */
    int deleteOldestUpTo(long ts, int limit) {
        return dao.deleteOldestUpTo(ts, limit);
    }

    /**
     * 取最旧的 N 条时间戳（升序；供容量协调计算跨表批次上界）
     */
    List<Long> oldestTimestamps(int limit) {
        List<Long> list = dao.oldestTimestamps(limit);
        return list != null ? list : new ArrayList<>();
    }

    @Override
    public IExportSnapshot<ExceptionRecord> openExportSnapshot(ExceptionQuery query) {
        final ExceptionQuery q = query != null ? query : ExceptionQuery.builder().build();
        // 锁序固定：exportGuard（读）→ dao；失败时释放读锁
        final ReentrantReadWriteLock.ReadLock readLock = exportGuard.readLock();
        readLock.lock();
        boolean open = false;
        try {
            warnIfMainThread("openExportSnapshot");
            final long maxId;
            final long capturedCount;
            try {
                // 边界捕获：已提交最大 ID + 该边界内的匹配数量（id 只增，后续新增不影响）
                maxId = dao.queryLong(new SimpleSQLiteQuery(
                        "SELECT COALESCE(MAX(id), 0) FROM exception_table"));
                List<Object> args = new ArrayList<>();
                StringBuilder sql = new StringBuilder(64);
                sql.append("SELECT COUNT(*) FROM exception_table");
                boolean hasWhere = appendWhereClause(sql, args, q);
                sql.append(hasWhere ? " AND " : " WHERE ").append("id <= ?");
                args.add(maxId);
                capturedCount = dao.queryLong(new SimpleSQLiteQuery(
                        sql.toString(), args.toArray()));
            } catch (Throwable t) {
                throw new ExportSnapshotException("exception snapshot boundary capture failed", t);
            }
            open = true;
            return new RoomExceptionExportSnapshot(readLock, q, maxId, capturedCount);
        } finally {
            if (!open) {
                readLock.unlock();
            }
        }
    }

    /**
     * 异常表一致性导出快照：读取保护 + (timestamp, id) 双键游标分页
     */
    private final class RoomExceptionExportSnapshot implements IExportSnapshot<ExceptionRecord> {
        /** 打开时持有的读取保护锁（close 时释放） */
        private final ReentrantReadWriteLock.ReadLock readLock;
        /** 快照查询条件 */
        private final ExceptionQuery query;
        /** 捕获的已提交最大 ID 边界 */
        private final long maxId;
        /** 边界内匹配总数 */
        private final long capturedCount;
        /** 快照次序方向（跟随查询条件） */
        private final boolean descending;
        /** 游标：上一批最后一条的 (timestamp, id)；null 表示首页 */
        private Long cursorTimestamp;
        private Long cursorId;
        /** 是否已读完 */
        private boolean exhausted = false;
        /** 是否已关闭 */
        private boolean closed = false;

        RoomExceptionExportSnapshot(ReentrantReadWriteLock.ReadLock readLock, ExceptionQuery query,
                                    long maxId, long capturedCount) {
            this.readLock = readLock;
            this.query = query;
            this.maxId = maxId;
            this.capturedCount = capturedCount;
            this.descending = query.getOrderBy() == OrderBy.DESC;
        }

        @Override
        public long getCapturedCount() {
            return capturedCount;
        }

        @Override
        public boolean isExhausted() {
            return exhausted;
        }

        @Override
        public List<ExceptionRecord> nextBatch(int maxRows) {
            if (closed) {
                throw new IllegalStateException("snapshot already closed");
            }
            if (exhausted || maxRows <= 0) {
                exhausted = true;
                return new ArrayList<>();
            }
            try {
                List<Object> args = new ArrayList<>();
                String sql = buildSnapshotPageSql(args, maxRows);
                List<ExceptionEntity> entities = dao.query(
                        new SimpleSQLiteQuery(sql, args.toArray()));
                List<ExceptionRecord> page = new ArrayList<>();
                long lastTimestamp = 0L;
                long lastId = 0L;
                if (entities != null) {
                    for (ExceptionEntity e : entities) {
                        ExceptionRecord r = ExceptionEntityConverter.toRecord(e);
                        if (r != null) {
                            page.add(r);
                            lastTimestamp = e.timestamp;
                            lastId = e.id;
                        }
                    }
                }
                if (page.isEmpty()) {
                    exhausted = true;
                    return page;
                }
                cursorTimestamp = lastTimestamp;
                cursorId = lastId;
                if (page.size() < maxRows) {
                    exhausted = true;
                }
                return page;
            } catch (Throwable t) {
                // 严格读取语义：失败抛出，不得伪装为空数据
                throw new ExportSnapshotException("exception snapshot page read failed", t);
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                readLock.unlock();
            }
        }

        /**
         * 构建游标分页 SQL：边界限定 + 双键次序 + LIMIT
         */
        private String buildSnapshotPageSql(List<Object> args, int limit) {
            StringBuilder sql = new StringBuilder(160);
            sql.append("SELECT * FROM exception_table");
            boolean hasWhere = appendWhereClause(sql, args, query);
            // ID 边界：限定捕获时刻已提交的集合
            sql.append(hasWhere ? " AND " : " WHERE ").append("id <= ?");
            args.add(maxId);
            // 游标条件：双键严格推进，同时间戳按 id 次序消歧（ISSUE-03）
            if (cursorTimestamp != null && cursorId != null) {
                if (descending) {
                    sql.append(" AND (timestamp < ? OR (timestamp = ? AND id < ?))");
                } else {
                    sql.append(" AND (timestamp > ? OR (timestamp = ? AND id > ?))");
                }
                args.add(cursorTimestamp);
                args.add(cursorTimestamp);
                args.add(cursorId);
            }
            sql.append(" ORDER BY timestamp ").append(descending ? "DESC" : "ASC")
                    .append(", id ").append(descending ? "DESC" : "ASC")
                    .append(" LIMIT ?");
            args.add(limit);
            return sql.toString();
        }
    }

    /**
     * 检测当前是否运行在 Android 主线程，如果是则输出警告日志
     * <p>数据库查询不应在主线程执行，否则会导致 ANR</p>
     * @param operation 当前操作名称（如 "queryExceptions"/"countExceptions"）
     */
    private void warnIfMainThread(String operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w("RoomExceptionStorage", "⚠️ " + operation
                    + "() called on main thread! Database operations on main thread may cause ANR."
                    + " Please move to a background thread.");
        }
    }
}
