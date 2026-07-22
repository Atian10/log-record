package com.atian10.logrecord.android;

import androidx.sqlite.db.SimpleSQLiteQuery;

import android.os.Looper;
import android.util.Log;

import com.atian10.logrecord.core.IExceptionStorage;
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
        if (policy == null || !policy.isEnabled()) {
            return 0;
        }
        int cleaned = 0;
        // 按天数清理
        if (policy.getKeepDays() > 0) {
            long threshold = System.currentTimeMillis()
                    - (long) policy.getKeepDays() * 24L * 60L * 60L * 1000L;
            cleaned += dao.cleanBefore(threshold);
        }
        // 按容量清理（超限时按数量保留当前的一半，与 RoomStorage 行为一致）
        long maxSizeBytes = policy.getMaxDbSizeMB() * 1024L * 1024L;
        if (maxSizeBytes > 0 && getDbSizeBytes() > maxSizeBytes) {
            long currentCount = dao.countAll();
            cleaned += dao.cleanByCount((int) Math.max(1, currentCount / 2));
        }
        // 按数量清理
        if (policy.getMaxRecordCount() > 0 && dao.countAll() > policy.getMaxRecordCount()) {
            cleaned += dao.cleanByCount((int) policy.getMaxRecordCount());
        }
        return cleaned;
    }

    @Override
    public int cleanBefore(long timestamp) {
        return dao.cleanBefore(timestamp);
    }

    @Override
    public int cleanByCount(int keepCount) {
        if (keepCount < 0) {
            keepCount = 0;
        }
        return dao.cleanByCount(keepCount);
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
