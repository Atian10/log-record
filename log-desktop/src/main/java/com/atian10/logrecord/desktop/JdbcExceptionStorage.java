package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.core.util.LikeEscapeUtil;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;
import com.atian10.logrecord.desktop.jdbc.LogTableSchema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 实现的异常存储
 * <p>
 * 实现 {@link IExceptionStorage}，将异常持久化到独立的 exception_table 表。
 * 与日志表完全独立，无外键关联。
 * </p>
 */
public final class JdbcExceptionStorage implements IExceptionStorage {

    private final JdbcHelper helper;

    /**
     * 构造 JdbcExceptionStorage
     * @param helper 已初始化的 JdbcHelper
     */
    public JdbcExceptionStorage(JdbcHelper helper) {
        if (helper == null) {
            throw new NullPointerException("helper == null");
        }
        this.helper = helper;
    }

    @Override
    public void write(ExceptionRecord record) {
        if (record == null) {
            return;
        }
        try {
            helper.executeUpdate(LogTableSchema.INSERT_EXCEPTION, toArgs(record));
        } catch (SQLException ignored) {
        }
    }

    @Override
    public void writeBatch(List<ExceptionRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Object[][] batchArgs = new Object[records.size()][];
        for (int i = 0; i < records.size(); i++) {
            batchArgs[i] = toArgs(records.get(i));
        }
        try {
            helper.executeBatch(LogTableSchema.INSERT_EXCEPTION, batchArgs);
        } catch (SQLException ignored) {
            for (ExceptionRecord r : records) {
                write(r);
            }
        }
    }

    @Override
    public List<ExceptionRecord> query(ExceptionQuery query) {
        if (query == null) {
            return new ArrayList<>();
        }
        List<Object> args = new ArrayList<>();
        String sql = buildQuerySql(query, args, false);
        try {
            return helper.query(sql, args.toArray(), rs -> {
                List<ExceptionRecord> result = new ArrayList<>();
                while (rs.next()) {
                    ExceptionRecord r = mapRow(rs);
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
                        "DELETE FROM exception_table WHERE timestamp < ?",
                        new Object[]{threshold});
            } catch (SQLException ignored) {
            }
        }
        // 按容量清理（超限时按数量保留当前的一半，与 JdbcStorage 行为一致）
        long maxSizeBytes = policy.getMaxDbSizeMB() * 1024L * 1024L;
        if (maxSizeBytes > 0 && helper.getDbSizeBytes() > maxSizeBytes) {
            long currentCount = getRecordCount();
            try {
                cleaned += helper.executeUpdate(
                        "DELETE FROM exception_table WHERE id NOT IN ("
                                + "SELECT id FROM exception_table ORDER BY timestamp DESC LIMIT ?)",
                        new Object[]{Math.max(1, currentCount / 2)});
            } catch (SQLException ignored) {
            }
        }
        // 按数量清理
        if (policy.getMaxRecordCount() > 0 && getRecordCount() > policy.getMaxRecordCount()) {
            try {
                cleaned += helper.executeUpdate(
                        "DELETE FROM exception_table WHERE id NOT IN ("
                                + "SELECT id FROM exception_table ORDER BY timestamp DESC LIMIT ?)",
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
                    "DELETE FROM exception_table WHERE timestamp < ?",
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
                    "DELETE FROM exception_table WHERE id NOT IN ("
                            + "SELECT id FROM exception_table ORDER BY timestamp DESC LIMIT ?)",
                    new Object[]{keepCount});
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public long getRecordCount() {
        try {
            return helper.queryLong("SELECT COUNT(*) FROM exception_table", null);
        } catch (SQLException e) {
            return 0L;
        }
    }

    @Override
    public long getDbSizeBytes() {
        return helper.getDbSizeBytes();
    }

    @Override
    public long count(ExceptionQuery query) {
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

    private Object[] toArgs(ExceptionRecord r) {
        return new Object[]{
                r.getTimestamp(),
                r.getExceptionClass(),
                r.getExceptionMessage(),
                r.getStackTrace(),
                r.getLogTag()
        };
    }

    private ExceptionRecord mapRow(ResultSet rs) throws SQLException {
        return new ExceptionRecord(
                rs.getLong("timestamp"),
                rs.getString("exception_class"),
                rs.getString("exception_msg"),
                rs.getString("stack_trace"),
                rs.getString("log_tag")
        );
    }

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
}
