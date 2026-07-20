package com.atian10.logrecord.android;

import androidx.sqlite.db.SimpleSQLiteQuery;

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
        // 使用全表聚合（条件过滤由业务方通过 query 后自行统计，
        // 此处提供全表统计以保证性能；如需条件统计可扩展 buildQuerySql 支持 GROUP BY）
        long total = dao.countAll();
        Map<LogLevel, Long> byLevel = new HashMap<>();
        for (LogDao.LevelCount lc : dao.countByLevel()) {
            LogLevel level = LogLevel.fromValue(lc.level);
            if (level != null) {
                byLevel.put(level, lc.count);
            }
        }
        Map<String, Long> byType = new HashMap<>();
        for (LogDao.TypeCount tc : dao.countByType()) {
            if (tc.type != null) {
                byType.put(tc.type, tc.count);
            }
        }
        Map<String, Long> byTag = new HashMap<>();
        for (LogDao.TagCount tc : dao.countByTag()) {
            if (tc.tag != null) {
                byTag.put(tc.tag, tc.count);
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
        // 按容量清理（超过上限时按天数清理；未配 keepDays 则按数量清理）
        long maxSizeBytes = policy.getMaxDbSizeMB() * 1024L * 1024L;
        if (maxSizeBytes > 0 && getDbSizeBytes() > maxSizeBytes) {
            if (policy.getKeepDays() > 0) {
                // 已按天数清理过，再清理一次更旧的
                long threshold = System.currentTimeMillis()
                        - (long) policy.getKeepDays() * 24L * 60L * 60L * 1000L;
                cleaned += dao.cleanBefore(threshold);
            } else {
                // 未配天数，按数量保留当前的一半
                long currentCount = dao.countAll();
                cleaned += dao.cleanByCount((int) Math.max(1, currentCount / 2));
            }
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
}
