package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.core.util.JsonUtil;
import com.atian10.logrecord.core.util.LikeEscapeUtil;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;
import com.atian10.logrecord.desktop.jdbc.LogTableSchema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 实现的日志存储
 * <p>
 * 实现 {@link IStorage}，将日志持久化到 SQLite。
 * 动态查询通过 SQL 拼接 + PreparedStatement 参数化实现，防 SQL 注入。
 * user_fields 的键值对查询采用 LIKE 模糊匹配。
 * </p>
 */
public final class JdbcStorage implements IStorage {

    private final JdbcHelper helper;

    /**
     * 构造 JdbcStorage
     * @param helper 已初始化的 JdbcHelper
     */
    public JdbcStorage(JdbcHelper helper) {
        if (helper == null) {
            throw new NullPointerException("helper == null");
        }
        this.helper = helper;
    }

    @Override
    public void write(LogRecord record) {
        if (record == null) {
            return;
        }
        try {
            helper.executeUpdate(LogTableSchema.INSERT_LOG, toArgs(record));
        } catch (SQLException ignored) {
            // 写入失败不抛异常，避免业务中断
        }
    }

    @Override
    public void writeBatch(List<LogRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Object[][] batchArgs = new Object[records.size()][];
        for (int i = 0; i < records.size(); i++) {
            batchArgs[i] = toArgs(records.get(i));
        }
        try {
            helper.executeBatch(LogTableSchema.INSERT_LOG, batchArgs);
        } catch (SQLException ignored) {
            // 批量失败降级逐条写
            for (LogRecord r : records) {
                write(r);
            }
        }
    }

    @Override
    public List<LogRecord> query(LogQuery query) {
        if (query == null) {
            return new ArrayList<>();
        }
        List<Object> args = new ArrayList<>();
        String sql = buildQuerySql(query, args, false);
        try {
            return helper.query(sql, args.toArray(), rs -> {
                List<LogRecord> result = new ArrayList<>();
                while (rs.next()) {
                    LogRecord r = mapRow(rs);
                    if (r != null) {
                        result.add(r);
                    }
                }
                return result;
            });
        } catch (SQLException ignored) {
            return new ArrayList<>();
        }
    }

    @Override
    public LogStatistics statistics(LogQuery query) {
        // 复用 count(query) 保证 total 与 WHERE 条件一致；
        // 三个维度（level/type/tag）通过 buildGroupBySql 拼接 GROUP BY 聚合 SQL，
        // 复用 appendWhereClause 保证 WHERE 条件与 query 一致。
        if (query == null) {
            query = LogQuery.builder().build();
        }
        long total = count(query);

        Map<LogLevel, Long> byLevel = new HashMap<>();
        try {
            List<Object> args = new ArrayList<>();
            String sql = buildGroupBySql(query, args, "level");
            byLevel = helper.query(sql, args.toArray(), rs -> {
                Map<LogLevel, Long> m = new HashMap<>();
                while (rs.next()) {
                    LogLevel level = LogLevel.fromValue(rs.getInt("level"));
                    if (level != null) {
                        m.put(level, rs.getLong("count"));
                    }
                }
                return m;
            });
        } catch (SQLException ignored) {
        }

        Map<String, Long> byType = new HashMap<>();
        try {
            List<Object> args = new ArrayList<>();
            String sql = buildGroupBySql(query, args, "type");
            byType = helper.query(sql, args.toArray(), rs -> {
                Map<String, Long> m = new HashMap<>();
                while (rs.next()) {
                    String type = rs.getString("type");
                    if (type != null) {
                        m.put(type, rs.getLong("count"));
                    }
                }
                return m;
            });
        } catch (SQLException ignored) {
        }

        Map<String, Long> byTag = new HashMap<>();
        try {
            List<Object> args = new ArrayList<>();
            String sql = buildGroupBySql(query, args, "tag");
            byTag = helper.query(sql, args.toArray(), rs -> {
                Map<String, Long> m = new HashMap<>();
                while (rs.next()) {
                    String tag = rs.getString("tag");
                    if (tag != null) {
                        m.put(tag, rs.getLong("count"));
                    }
                }
                return m;
            });
        } catch (SQLException ignored) {
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
            try {
                cleaned += helper.executeUpdate(
                        "DELETE FROM log_record WHERE timestamp < ?",
                        new Object[]{threshold});
            } catch (SQLException ignored) {
            }
        }
        // 按容量清理
        long maxSizeBytes = policy.getMaxDbSizeMB() * 1024L * 1024L;
        if (maxSizeBytes > 0 && helper.getDbSizeBytes() > maxSizeBytes) {
            if (policy.getKeepDays() > 0) {
                long threshold = System.currentTimeMillis()
                        - (long) policy.getKeepDays() * 24L * 60L * 60L * 1000L;
                try {
                    cleaned += helper.executeUpdate(
                            "DELETE FROM log_record WHERE timestamp < ?",
                            new Object[]{threshold});
                } catch (SQLException ignored) {
                }
            } else {
                long currentCount = getRecordCount();
                try {
                    cleaned += helper.executeUpdate(
                            "DELETE FROM log_record WHERE id NOT IN ("
                                    + "SELECT id FROM log_record ORDER BY timestamp DESC LIMIT ?)",
                            new Object[]{Math.max(1, currentCount / 2)});
                } catch (SQLException ignored) {
                }
            }
        }
        // 按数量清理
        if (policy.getMaxRecordCount() > 0 && getRecordCount() > policy.getMaxRecordCount()) {
            try {
                cleaned += helper.executeUpdate(
                        "DELETE FROM log_record WHERE id NOT IN ("
                                + "SELECT id FROM log_record ORDER BY timestamp DESC LIMIT ?)",
                        new Object[]{(int) policy.getMaxRecordCount()});
            } catch (SQLException ignored) {
            }
        }
        return cleaned;
    }

    @Override
    public int cleanBefore(long timestamp) {
        try {
            return helper.executeUpdate(
                    "DELETE FROM log_record WHERE timestamp < ?",
                    new Object[]{timestamp});
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public int cleanByCount(int keepCount) {
        if (keepCount < 0) {
            keepCount = 0;
        }
        try {
            return helper.executeUpdate(
                    "DELETE FROM log_record WHERE id NOT IN ("
                            + "SELECT id FROM log_record ORDER BY timestamp DESC LIMIT ?)",
                    new Object[]{keepCount});
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public long getRecordCount() {
        try {
            return helper.queryLong("SELECT COUNT(*) FROM log_record", null);
        } catch (SQLException e) {
            return 0L;
        }
    }

    @Override
    public long getDbSizeBytes() {
        return helper.getDbSizeBytes();
    }

    @Override
    public long count(LogQuery query) {
        if (query == null) {
            return getRecordCount();
        }
        List<Object> args = new ArrayList<>();
        String sql = buildQuerySql(query, args, true);
        try {
            return helper.queryLong(sql, args.toArray());
        } catch (SQLException e) {
            return 0L;
        }
    }

    // ===== 内部方法 =====

    /**
     * LogRecord → INSERT 参数数组
     */
    private Object[] toArgs(LogRecord r) {
        return new Object[]{
                r.getTimestamp(),
                r.getLevel() == null ? 0 : r.getLevel().getValue(),
                r.getType(),
                r.getTag(),
                r.getThreadName(),
                r.getThreadId(),
                r.getMethodName(),
                r.getLineNumber(),
                r.getMessage(),
                JsonUtil.mapToJson(r.getUserFields()),
                r.getVersionTag(),
                r.isHasException() ? 1 : 0
        };
    }

    /**
     * ResultSet → LogRecord
     */
    private LogRecord mapRow(ResultSet rs) throws SQLException {
        int level = rs.getInt("level");
        LogLevel logLevel = LogLevel.fromValue(level);
        String userFieldsJson = rs.getString("user_fields");
        Map<String, String> userFields = JsonUtil.jsonToMap(userFieldsJson);
        Map<String, String> mutableFields = (userFields == null || userFields.isEmpty())
                ? null : new HashMap<>(userFields);
        return new LogRecord(
                rs.getLong("timestamp"),
                logLevel,
                rs.getString("type"),
                rs.getString("tag"),
                rs.getString("thread_name"),
                rs.getLong("thread_id"),
                rs.getString("method_name"),
                rs.getInt("line_number"),
                rs.getString("version_tag"),
                rs.getInt("has_exception") == 1,
                rs.getString("message"),
                mutableFields
        );
    }

    /**
     * 构建查询 SQL
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
}
