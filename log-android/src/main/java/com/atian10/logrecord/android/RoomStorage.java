package com.atian10.logrecord.android;

import androidx.sqlite.db.SimpleSQLiteQuery;

import android.os.Looper;
import android.util.Log;

import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.core.util.LikeEscapeUtil;
import com.atian10.logrecord.android.room.LogDao;
import com.atian10.logrecord.android.room.LogDatabase;
import com.atian10.logrecord.android.room.LogEntity;
import com.atian10.logrecord.android.room.LogEntityConverter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Room 实现的日志存储
 * <p>
 * 实现 {@link IStorage}，将日志持久化到 Room/SQLite。
 * 动态查询通过 {@link SimpleSQLiteQuery} 拼接 SQL 实现。
 * user_fields 的键值对查询采用 LIKE 模糊匹配（兼容性优于 json_extract）。
 * </p>
 */
public final class RoomStorage implements IStorage {

    private final LogDatabase database;
    private final LogDao dao;

    /**
     * 构造 RoomStorage
     * @param database 已构建的 LogDatabase
     */
    public RoomStorage(LogDatabase database) {
        if (database == null) {
            throw new NullPointerException("database == null");
        }
        this.database = database;
        this.dao = database.logDao();
    }

    @Override
    public void write(LogRecord record) {
        LogEntity entity = LogEntityConverter.toEntity(record);
        if (entity != null) {
            dao.insert(entity);
        }
    }

    @Override
    public void writeBatch(List<LogRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<LogEntity> entities = new ArrayList<>(records.size());
        for (LogRecord r : records) {
            LogEntity e = LogEntityConverter.toEntity(r);
            if (e != null) {
                entities.add(e);
            }
        }
        if (!entities.isEmpty()) {
            dao.insertAll(entities);
        }
    }

    @Override
    public List<LogRecord> query(LogQuery query) {
        warnIfMainThread("query");
        if (query == null) {
            return new ArrayList<>();
        }
        List<Object> args = new ArrayList<>();
        String sql = buildQuerySql(query, args, false);
        List<LogEntity> entities = dao.query(new SimpleSQLiteQuery(sql, args.toArray()));
        List<LogRecord> result = new ArrayList<>();
        if (entities != null) {
            for (LogEntity e : entities) {
                LogRecord r = LogEntityConverter.toRecord(e);
                if (r != null) {
                    result.add(r);
                }
            }
        }
        return result;
    }

    @Override
    public LogStatistics statistics(LogQuery query) {
        warnIfMainThread("statistics");
        // 复用 count(query) 保证 total 与 WHERE 条件一致；
        // 三个维度（level/type/tag）通过 buildGroupBySql 拼接 GROUP BY 聚合 SQL，
        // 复用 appendWhereClause 保证 WHERE 条件与 query 一致。
        if (query == null) {
            query = LogQuery.builder().build();
        }
        long total = count(query);

        Map<LogLevel, Long> byLevel = new HashMap<>();
        {
            List<Object> args = new ArrayList<>();
            String sql = buildGroupBySql(query, args, "level");
            List<LogDao.LevelCount> list = dao.queryLevelCount(new SimpleSQLiteQuery(sql, args.toArray()));
            if (list != null) {
                for (LogDao.LevelCount lc : list) {
                    LogLevel level = LogLevel.fromValue(lc.level);
                    if (level != null) {
                        byLevel.put(level, lc.count);
                    }
                }
            }
        }

        Map<String, Long> byType = new HashMap<>();
        {
            List<Object> args = new ArrayList<>();
            String sql = buildGroupBySql(query, args, "type");
            List<LogDao.TypeCount> list = dao.queryTypeCount(new SimpleSQLiteQuery(sql, args.toArray()));
            if (list != null) {
                for (LogDao.TypeCount tc : list) {
                    if (tc.type != null) {
                        byType.put(tc.type, tc.count);
                    }
                }
            }
        }

        Map<String, Long> byTag = new HashMap<>();
        {
            List<Object> args = new ArrayList<>();
            String sql = buildGroupBySql(query, args, "tag");
            List<LogDao.TagCount> list = dao.queryTagCount(new SimpleSQLiteQuery(sql, args.toArray()));
            if (list != null) {
                for (LogDao.TagCount tc : list) {
                    if (tc.tag != null) {
                        byTag.put(tc.tag, tc.count);
                    }
                }
            }
        }
        return new LogStatistics(total, byLevel, byType, byTag);
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
        // 按容量清理（超限时按数量保留当前的一半，避免重复 cleanBefore 无效调用）
        long maxSizeBytes = policy.getMaxDbSizeMB() * 1024L * 1024L;
        if (maxSizeBytes > 0 && getDbSizeBytes() > maxSizeBytes) {
            // 容量超限：统一按数量保留当前的一半（不论是否配置 keepDays）
            // 原实现按 keepDays 再次 cleanBefore 相同 threshold 必返回 0，逻辑无效
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
            String path = database.getOpenHelper().getReadableDatabase().getPath();
            if (path != null) {
                File f = new File(path);
                if (f.exists()) {
                    return f.length();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    @Override
    public long count(LogQuery query) {
        warnIfMainThread("count");
        if (query == null) {
            return dao.countAll();
        }
        List<Object> args = new ArrayList<>();
        String sql = buildQuerySql(query, args, true);
        return dao.count(new SimpleSQLiteQuery(sql, args.toArray()));
    }

    /**
     * 构建 SQL 查询语句
     * @param query 查询条件
     * @param args  参数列表（输出）
     * @param countMode true 构造 COUNT(*) 查询，false 构造数据查询
     * @return SQL 字符串
     */
    private String buildQuerySql(LogQuery query, List<Object> args, boolean countMode) {
        StringBuilder sql = new StringBuilder(128);
        if (countMode) {
            sql.append("SELECT COUNT(*) FROM log_record");
        } else {
            sql.append("SELECT * FROM log_record");
        }
        appendWhereClause(sql, args, query);
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
     * 构建 GROUP BY 聚合查询 SQL
     * <p>用于 statistics 方法，按指定列分组聚合并附加 WHERE 条件</p>
     * @param query 查询条件（用于 WHERE 子句）
     * @param args  参数列表（输出）
     * @param groupByColumn 分组列名（如 "level"/"type"/"tag"）
     * @return SQL 字符串，形如 SELECT {col}, COUNT(*) AS count FROM log_record WHERE ... GROUP BY {col}
     */
    private String buildGroupBySql(LogQuery query, List<Object> args, String groupByColumn) {
        StringBuilder sql = new StringBuilder(128);
        sql.append("SELECT ").append(groupByColumn)
                .append(", COUNT(*) AS count FROM log_record");
        appendWhereClause(sql, args, query);
        sql.append(" GROUP BY ").append(groupByColumn);
        return sql.toString();
    }

    /**
     * 追加 WHERE 子句到 SQL 构造器
     * <p>抽取公共 WHERE 拼接逻辑，供 buildQuerySql 和 buildGroupBySql 复用</p>
     * @param sql SQL 构造器
     * @param args 参数列表（输出）
     * @param query 查询条件
     */
    private void appendWhereClause(StringBuilder sql, List<Object> args, LogQuery query) {
        boolean hasWhere = false;
        if (query.getLevel() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("level = ?");
            args.add(query.getLevel().getValue());
            hasWhere = true;
        }
        if (query.getType() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("type = ?");
            args.add(query.getType());
            hasWhere = true;
        }
        if (query.getTag() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("tag = ?");
            args.add(query.getTag());
            hasWhere = true;
        }
        if (query.getKeyword() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("message LIKE ? ESCAPE '\\'");
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
        if (query.getVersionTag() != null) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("version_tag = ?");
            args.add(query.getVersionTag());
            hasWhere = true;
        }
        // userFields 键值对查询：LIKE 模糊匹配 JSON（转义 key/value 中的特殊字符）
        if (query.getUserFields() != null && !query.getUserFields().isEmpty()) {
            for (Map.Entry<String, String> e : query.getUserFields().entrySet()) {
                sql.append(hasWhere ? " AND " : " WHERE ")
                        .append("user_fields LIKE ? ESCAPE '\\'");
                args.add("%\"" + LikeEscapeUtil.escape(e.getKey())
                        + "\":\"" + LikeEscapeUtil.escape(e.getValue()) + "\"%");
                hasWhere = true;
            }
        }
    }

    /**
     * 检测当前是否运行在 Android 主线程，如果是则输出警告日志
     * <p>数据库查询/统计不应在主线程执行，否则会导致 ANR</p>
     * @param operation 当前操作名称（如 "query"/"statistics"/"count"）
     */
    private void warnIfMainThread(String operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w("RoomStorage", "⚠️ " + operation
                    + "() called on main thread! Database operations on main thread may cause ANR."
                    + " Please move to a background thread.");
        }
    }
}
