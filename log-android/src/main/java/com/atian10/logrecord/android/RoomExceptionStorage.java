package com.atian10.logrecord.android;

import com.atian10.logrecord.core.DatabaseOperationGuard;

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
    /** 同一数据库的操作许可，覆盖复合访问及整个导出快照。 */
    private final DatabaseOperationGuard operationGuard;

    private final LogDatabase database;
    private final ExceptionDao dao;

    /**
     * 导出读取保护：快照复制持读锁，清理持写锁互斥
     * <p>锁序固定为操作许可 → exportGuard → 维护锁 → dao/Room 内部锁，
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
        this.operationGuard = database.getOperationGuard();
        this.dao = database.exceptionDao();
    }

    @Override
    public void write(ExceptionRecord record) {
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            writeInternal(record);
        }
    }
    /** 在存活许可内转换并写入单条记录，错误由原存储契约上报。 */
    private void writeInternal(ExceptionRecord record) {
        ExceptionEntity entity = ExceptionEntityConverter.toEntity(record);
        if (entity != null) {
            dao.insert(entity);
        }
    }

    @Override
    public void writeBatch(List<ExceptionRecord> records) {
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            writeBatchInternal(records);
        }
    }
    /** 在同一外层存活许可内提交整批，保留逐条或事务级结果。 */
    private void writeBatchInternal(List<ExceptionRecord> records) {
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
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return queryInternal(query);
        }
    }
    /** 在存活许可内完成查询及实体转换，使用原查询的错误兼容语义。 */
    private List<ExceptionRecord> queryInternal(ExceptionQuery query) {
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
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
            return cleanInternal(policy);
        }
    }
    /** 表级清理先取导出写锁再进入数据库访问，不驱动全库容量删除。 */
    private int cleanInternal(CleanPolicy policy) {
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → dao）
        exportGuard.writeLock().lock();
        try (DatabaseOperationGuard.Access access = operationGuard.read()) {
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
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
            return cleanBeforeInternal(timestamp);
        }
    }
    /** 在导出写锁内删除截止时间之前的记录，阻止与快照复制交错。 */
    private int cleanBeforeInternal(long timestamp) {
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → dao）
        exportGuard.writeLock().lock();
        try (DatabaseOperationGuard.Access access = operationGuard.read()) {
            return dao.cleanBefore(timestamp);
        } finally {
            exportGuard.writeLock().unlock();
        }
    }

    @Override
    public int cleanByCount(int keepCount) {
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
            return cleanByCountInternal(keepCount);
        }
    }
    /** 在导出写锁内保留指定数量的最新记录，再删除其余记录。 */
    private int cleanByCountInternal(int keepCount) {
        if (keepCount < 0) {
            keepCount = 0;
        }
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → dao）
        exportGuard.writeLock().lock();
        try (DatabaseOperationGuard.Access access = operationGuard.read()) {
            return dao.cleanByCount(keepCount);
        } finally {
            exportGuard.writeLock().unlock();
        }
    }

    @Override
    public long getRecordCount() {
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return getRecordCountInternal();
        }
    }
    /** 在当前存活许可内取得表总数。 */
    private long getRecordCountInternal() {
        return dao.countAll();
    }

    @Override
    public long getDbSizeBytes() {
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return getDbSizeBytesInternal();
        }
    }
    /** 在当前存活许可内读取原存储的容量展示值；严格维护度量由协调器负责。 */
    private long getDbSizeBytesInternal() {
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
        // 外层许可覆盖整个方法，数据库不会在嵌套访问之间被关闭。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return countInternal(query);
        }
    }
    /** 在当前存活许可内按统一查询条件统计数量。 */
    private long countInternal(ExceptionQuery query) {
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

    @Override
    public IExportSnapshot<ExceptionRecord> openExportSnapshot(ExceptionQuery query) {
        // 成功后许可转交快照，构造失败则在本方法释放。
        final DatabaseOperationGuard.Scope operation = operationGuard.enter();
        final ExceptionQuery q = query != null ? query : ExceptionQuery.builder().build();
        // 锁序固定：exportGuard（读）→ dao；失败时释放读锁
        final ReentrantReadWriteLock.ReadLock readLock = exportGuard.readLock();
        readLock.lock();
        boolean open = false;
        try (DatabaseOperationGuard.Access access = operationGuard.read()) {
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
            return new RoomExceptionExportSnapshot(readLock, q, maxId, capturedCount, operation);
        } finally {
            if (!open) {
                readLock.unlock();
                operation.close();
            }
        }
    }

    /**
     * 异常表一致性导出快照：读取保护 + (timestamp, id) 双键游标分页
     */
    private final class RoomExceptionExportSnapshot implements IExportSnapshot<ExceptionRecord> {
        /** 打开时持有的读取保护锁（close 时释放） */
        private final ReentrantReadWriteLock.ReadLock readLock;
        /** 从 open 转移的许可，释放表锁后归还。 */
        private final DatabaseOperationGuard.Scope operation;
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
                                    long maxId, long capturedCount,
                DatabaseOperationGuard.Scope operation) {
            this.readLock = readLock;
            this.operation = operation;
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
            // 每页短暂持维护读锁，快照许可在分页间隙仍保持活跃。
            try (DatabaseOperationGuard.Access access = operationGuard.read()) {
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
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                readLock.unlock();
                operation.close();
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
