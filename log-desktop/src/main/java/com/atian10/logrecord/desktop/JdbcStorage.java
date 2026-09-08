package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.BatchWriteException;
import com.atian10.logrecord.core.ExportSnapshotException;
import com.atian10.logrecord.core.IExportSnapshot;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.core.util.JsonUtil;
import com.atian10.logrecord.core.util.LikeEscapeUtil;
import com.atian10.logrecord.desktop.jdbc.BatchRolledBackException;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;
import com.atian10.logrecord.desktop.jdbc.LogTableSchema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JDBC 实现的日志存储
 * <p>
 * 实现 {@link IStorage}，将日志持久化到 SQLite。
 * 动态查询通过 SQL 拼接 + PreparedStatement 参数化实现，防 SQL 注入。
 * user_fields 的键值对查询按与写入一致的规范 JSON 成员片段，
 * 用参数化 {@code instr} 子串匹配（大小写敏感）。
 * </p>
 */
public final class JdbcStorage implements IStorage {

    private final JdbcHelper helper;

    /**
     * 导出读取保护：快照复制持读锁，清理持写锁互斥
     * <p>锁序固定为 exportGuard → helper（JdbcHelper 内部 monitor），
     * 任何持 helper 锁的路径不得再等待本锁，避免死锁</p>
     */
    private final ReentrantReadWriteLock exportGuard = new ReentrantReadWriteLock();

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
        } catch (SQLException e) {
            // 写入失败必须向引擎传递，禁止静默吞掉（ISSUE-02）
            throw new BatchWriteException(1, 0, "log write failed: " + e.getMessage(), e);
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
        } catch (BatchRolledBackException e) {
            // 事务已确认回滚：由本存储层执行唯一的降级重放（逐条），引擎不再叠加重试
            int saved = 0;
            SQLException lastFailure = null;
            for (LogRecord r : records) {
                try {
                    helper.executeUpdate(LogTableSchema.INSERT_LOG, toArgs(r));
                    saved++;
                } catch (SQLException ex) {
                    lastFailure = ex;
                }
            }
            if (saved < records.size()) {
                throw new BatchWriteException(records.size(), saved,
                        "log batch replay partially failed: saved " + saved + "/"
                                + records.size(),
                        lastFailure != null ? lastFailure : e);
            }
        } catch (SQLException e) {
            // 提交结果不确定或回滚失败：禁止自动重放，整批按失败上报
            throw new BatchWriteException(records.size(), 0,
                    "log batch outcome uncertain, replay disabled: " + e.getMessage(), e);
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
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → helper）
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
                try {
                    cleaned += helper.executeUpdate(
                            "DELETE FROM log_record WHERE timestamp < ?",
                            new Object[]{threshold});
                } catch (SQLException ignored) {
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
        } finally {
            exportGuard.writeLock().unlock();
        }
    }

    @Override
    public int cleanBefore(long timestamp) {
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → helper）
        exportGuard.writeLock().lock();
        try {
            return helper.executeUpdate(
                    "DELETE FROM log_record WHERE timestamp < ?",
                    new Object[]{timestamp});
        } catch (SQLException e) {
            return 0;
        } finally {
            exportGuard.writeLock().unlock();
        }
    }

    @Override
    public int cleanByCount(int keepCount) {
        if (keepCount < 0) {
            keepCount = 0;
        }
        // 清理与导出快照复制互斥：先取写锁（锁序 exportGuard → helper）
        exportGuard.writeLock().lock();
        try {
            return helper.executeUpdate(
                    "DELETE FROM log_record WHERE id NOT IN ("
                            + "SELECT id FROM log_record ORDER BY timestamp DESC LIMIT ?)",
                    new Object[]{keepCount});
        } catch (SQLException e) {
            return 0;
        } finally {
            exportGuard.writeLock().unlock();
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
     * <p>抽取公共 WHERE 拼接逻辑，供 buildQuerySql、buildGroupBySql 与导出快照复用</p>
     * @param sql SQL 构造器
     * @param args 参数列表（输出）
     * @param query 查询条件
     * @return 是否已追加过条件（用于调用方决定后续 AND/WHERE 前缀）
     */
    private boolean appendWhereClause(StringBuilder sql, List<Object> args, LogQuery query) {
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
        // userFields 键值对查询：按与写入一致的规范 JSON 成员片段做参数化子串匹配。
        // instr 为大小写敏感的精确字节匹配，正确处理引号/反斜杠/换行/百分号/下划线/null，
        // 避免 LIKE 的大小写不敏感与原始文本转义跟 JSON 编码不一致的问题（ISSUE-07）。
        if (query.getUserFields() != null && !query.getUserFields().isEmpty()) {
            for (Map.Entry<String, String> e : query.getUserFields().entrySet()) {
                sql.append(hasWhere ? " AND " : " WHERE ")
                        .append("instr(user_fields, ?) > 0");
                args.add(JsonUtil.userFieldMemberJson(e.getKey(), e.getValue()));
                hasWhere = true;
            }
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
    public IExportSnapshot<LogRecord> openExportSnapshot(LogQuery query) {
        final LogQuery q = query != null ? query : LogQuery.builder().build();
        // 锁序固定：exportGuard（读）→ helper；失败时释放读锁
        final ReentrantReadWriteLock.ReadLock readLock = exportGuard.readLock();
        readLock.lock();
        boolean open = false;
        try {
            final long maxId;
            final long capturedCount;
            try {
                // 边界捕获：已提交最大 ID + 该边界内的匹配数量（id 只增，后续新增不影响）
                maxId = helper.queryLong("SELECT COALESCE(MAX(id), 0) FROM log_record", null);
                List<Object> args = new ArrayList<>();
                StringBuilder sql = new StringBuilder(64);
                sql.append("SELECT COUNT(*) FROM log_record");
                boolean hasWhere = appendWhereClause(sql, args, q);
                sql.append(hasWhere ? " AND " : " WHERE ").append("id <= ?");
                args.add(maxId);
                capturedCount = helper.queryLong(sql.toString(), args.toArray());
            } catch (SQLException e) {
                throw new ExportSnapshotException("log snapshot boundary capture failed", e);
            }
            open = true;
            return new JdbcLogExportSnapshot(readLock, q, maxId, capturedCount);
        } finally {
            if (!open) {
                readLock.unlock();
            }
        }
    }

    /**
     * 日志表一致性导出快照：读取保护 + (timestamp, id) 双键游标分页
     */
    private final class JdbcLogExportSnapshot implements IExportSnapshot<LogRecord> {
        /** 打开时持有的读取保护锁（close 时释放） */
        private final ReentrantReadWriteLock.ReadLock readLock;
        /** 快照查询条件 */
        private final LogQuery query;
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

        JdbcLogExportSnapshot(ReentrantReadWriteLock.ReadLock readLock, LogQuery query,
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
        public List<LogRecord> nextBatch(int maxRows) {
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
                // 同时取回本批最后一条的 (timestamp, id) 作为下一页游标
                final long[] lastTimestamp = new long[1];
                final long[] lastId = new long[1];
                List<LogRecord> page = helper.query(sql, args.toArray(), rs -> {
                    List<LogRecord> result = new ArrayList<>();
                    while (rs.next()) {
                        LogRecord r = mapRow(rs);
                        if (r != null) {
                            result.add(r);
                            lastTimestamp[0] = rs.getLong("timestamp");
                            lastId[0] = rs.getLong("id");
                        }
                    }
                    return result;
                });
                if (page.isEmpty()) {
                    exhausted = true;
                    return page;
                }
                cursorTimestamp = lastTimestamp[0];
                cursorId = lastId[0];
                if (page.size() < maxRows) {
                    exhausted = true;
                }
                return page;
            } catch (SQLException e) {
                // 严格读取语义：失败抛出，不得伪装为空数据
                throw new ExportSnapshotException("log snapshot page read failed", e);
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
            sql.append("SELECT * FROM log_record");
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
}
