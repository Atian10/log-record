package com.atian10.logrecord.desktop.jdbc;

import com.atian10.logrecord.core.DatabaseOperationGuard;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 工具类
 * <p>
 * 单例 Connection + WAL 模式，提供连接管理、建表、通用查询能力。
 * 常规写操作通过受保护的语句入口执行并绑定参数；容量维护在独占窗口内执行跨表事务。
 * 连接关闭由 {@link #close()} 释放（通常在 LogManager.shutdown 时调用）。
 * </p>
 * <p>
 * 线程安全：数据库方法先取存活许可和维护读锁，再进入连接 monitor。
 * 容量维护先取两表导出锁及维护写锁，关闭则在所有操作和排空结束后释放连接。
 * </p>
 */
public final class JdbcHelper {

    private final String dbPath;
    /** 同一连接、两张表和管理器共享的生命周期与维护保护。 */
    private final DatabaseOperationGuard operationGuard = new DatabaseOperationGuard();
    /** 平台接线使用唯一保护实例，禁止为每张表分别创建。 */
    public DatabaseOperationGuard getOperationGuard() { return operationGuard; }
    private Connection connection;

    /**
     * 构造并初始化数据库
     * @param dbPath 数据库文件路径（如 "/var/log/app/log_record.db"）
     * @throws SQLException 数据库初始化失败
     */
    public JdbcHelper(String dbPath) throws SQLException {
        if (dbPath == null || dbPath.isEmpty()) {
            throw new IllegalArgumentException("dbPath is null or empty");
        }
        this.dbPath = dbPath;
        this.connection = openConnection(dbPath);
        initSchema();
    }

    /**
     * 获取数据库路径
     */
    public String getDbPath() {
        return dbPath;
    }

    /**
     * 获取底层 Connection（仅供迁移等高级操作使用）
     * <p>调用方不应关闭此 Connection，由 {@link #close()} 统一管理。
     * 返回后的外部操作不受本库快照、维护及关闭保护；仅用于调用方自行协调的高级操作。</p>
     * @return Connection
     * @throws SQLException 连接已关闭
     */
    public synchronized Connection getConnection() throws SQLException {
        ensureOpen();
        return connection;
    }

    /**
     * 执行非查询 SQL（INSERT/UPDATE/DELETE/DDL）
     * @param sql SQL 语句
     * @param args 参数（按占位符顺序）
     * @return 受影响行数
     * @throws SQLException 执行失败
     */
    /** 先取得操作许可和维护读锁，再进入连接 monitor，保持全库锁序。 */
    public int executeUpdate(String sql, Object[] args) throws SQLException {
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return executeUpdateLocked(sql, args);
        }
    }
    /** 在连接 monitor 内完成完整语句及结果消费；外层已经持有操作保护。 */
    private synchronized int executeUpdateLocked(String sql, Object[] args) throws SQLException {
        ensureOpen();
        if (!connection.getAutoCommit()) throw new SQLException("standalone update requires autoCommit");
        // statement 的清理失败不能覆盖已经确认的写入结果。
        PreparedStatement statement = null;
        SQLException failure = null;
        boolean executed = false;
        int changed = 0;
        try {
            statement = connection.prepareStatement(sql);
            if (args != null) {
                for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            }
            changed = statement.executeUpdate();
            executed = true;
        } catch (SQLException error) {
            failure = error;
        } finally {
            failure = closeStatement(statement, failure);
        }
        if (failure != null) throw new WriteOutcomeException(executed
                ? com.atian10.logrecord.core.BatchWriteException.ItemOutcome.SAVED
                : com.atian10.logrecord.core.BatchWriteException.ItemOutcome.UNKNOWN, failure);
        return changed;
    }
    /**
     * 批量插入（事务）
     * <p>
     * 事务失败时先执行回滚以确认事务终态：
     * <ul>
     *   <li>回滚成功：抛 {@link BatchRolledBackException}，表示数据确定未提交、可安全重放</li>
     *   <li>回滚失败或提交阶段失败后无法确认：抛原始 {@link SQLException}，
     *       表示事务结果不确定，调用方禁止自动重放</li>
     * </ul>
     * 回滚失败与恢复 autoCommit 状态的失败均以 suppressed 证据附加在最终异常上，
     * 不再静默吞掉。
     * </p>
     * @param sql INSERT 语句
     * @param batchArgs 批量参数（每行一组参数）
     * @return 插入总行数
     * @throws SQLException 执行失败；具体含义见上述事务终态规则
     */
    /** 先取得操作许可和维护读锁，再进入连接 monitor，保持全库锁序。 */
    public int executeBatch(String sql, Object[][] batchArgs) throws SQLException {
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return executeBatchLocked(sql, batchArgs);
        }
    }
    /** 在连接 monitor 内完成完整语句及结果消费；外层已经持有操作保护。 */
    private synchronized int executeBatchLocked(String sql, Object[][] batchArgs) throws SQLException {
        ensureOpen();
        if (!connection.getAutoCommit()) throw new SQLException("nested batch transaction is unsupported");
        // 三个提交阶段分别决定是否允许恢复连接和重放；commit 抛错不能证明未提交。
        boolean commitStarted = false;
        boolean committed = false;
        boolean rolledBack = false;
        boolean restored = false;
        PreparedStatement statement = null;
        SQLException failure = null;
        int total = 0;
        try {
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(sql);
            if (batchArgs != null) {
                for (Object[] args : batchArgs) {
                    statement.clearParameters();
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                    }
                    statement.addBatch();
                }
            }
            // 更新计数只供调用方显示；提交正常返回才构成持久化成功证据。
            int[] counts = statement.executeBatch();
            commitStarted = true;
            connection.commit();
            committed = true;
            for (int count : counts) if (count > 0) total += count;
        } catch (SQLException error) {
            failure = error;
            if (!commitStarted) {
                try { connection.rollback(); rolledBack = true; }
                catch (SQLException rollbackError) { failure.addSuppressed(rollbackError); }
            }
        } finally {
            failure = closeStatement(statement, failure);
            if (committed || rolledBack) {
                try { connection.setAutoCommit(true); restored = true; }
                catch (SQLException restoreError) {
                    if (failure == null) failure = restoreError;
                    else failure.addSuppressed(restoreError);
                }
            }
            if (!restored) {
                // 未知事务禁止通过 setAutoCommit(true) 意外提交；隔离连接后不再允许复用。
                try { connection.close(); }
                catch (SQLException closeError) {
                    if (failure == null) failure = closeError;
                    else failure.addSuppressed(closeError);
                }
                connection = null;
            }
        }
        if (failure != null) {
            if (committed) throw new WriteOutcomeException(
                    com.atian10.logrecord.core.BatchWriteException.ItemOutcome.SAVED, failure);
            if (rolledBack && restored) throw new BatchRolledBackException(
                    "batch rolled back before commit; replay permitted", failure);
            throw new WriteOutcomeException(rolledBack
                    ? com.atian10.logrecord.core.BatchWriteException.ItemOutcome.FAILED
                    : com.atian10.logrecord.core.BatchWriteException.ItemOutcome.UNKNOWN, failure);
        }
        return total;
    }

    /** 携带写入结果的 SQL 异常；清理错误与已保存结果可以同时存在。 */
    public static final class WriteOutcomeException extends SQLException {
        /** 同一语句或整个事务的已确认结果。 */
        private final com.atian10.logrecord.core.BatchWriteException.ItemOutcome outcome;
        /** 保留初始异常以及其清理证据，不触发第二次写入。 */
        WriteOutcomeException(com.atian10.logrecord.core.BatchWriteException.ItemOutcome outcome,
                              SQLException cause) {
            super("write outcome " + outcome + ": " + cause.getMessage(), cause);
            this.outcome = outcome;
        }
        /** 返回本次语句的结果，UNKNOWN 不得解释为未提交。 */
        public com.atian10.logrecord.core.BatchWriteException.ItemOutcome getOutcome() { return outcome; }
    }

    /** 关闭语句并把次要错误附加到第一处失败，保留写入阶段的证据。 */
    private SQLException closeStatement(PreparedStatement statement, SQLException failure) {
        if (statement != null) {
            try { statement.close(); }
            catch (SQLException closeError) {
                if (failure == null) return closeError;
                failure.addSuppressed(closeError);
            }
        }
        return failure;
    }
    /**
     * 查询单值（如 COUNT(*)）
     * @param sql 查询语句
     * @param args 参数
     * @return 首行首列的 long 值
     * @throws SQLException 执行失败
     */
    /** 先取得操作许可和维护读锁，再进入连接 monitor，保持全库锁序。 */
    public long queryLong(String sql, Object[] args) throws SQLException {
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return queryLongLocked(sql, args);
        }
    }
    /** 在连接 monitor 内完成完整语句及结果消费；外层已经持有操作保护。 */
    private synchronized long queryLongLocked(String sql, Object[] args) throws SQLException {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        }
    }

    /**
     * 查询结果集处理器（回调模式，保证 PreparedStatement 和 ResultSet 正确关闭）
     * @param <T> 返回值类型
     */
    public interface ResultSetHandler<T> {
        /**
         * 处理结果集
         * @param rs 结果集（无需调用方关闭）
         * @return 处理结果
         * @throws SQLException 处理失败
         */
        T handle(ResultSet rs) throws SQLException;
    }

    /**
     * 查询并通过回调处理结果集（推荐使用，自动管理 PreparedStatement/ResultSet 生命周期）
     * @param sql 查询语句
     * @param args 参数
     * @param handler 结果集处理器
     * @param <T> 返回值类型
     * @return 处理结果
     * @throws SQLException 执行失败
     */
    /** 先取得操作许可和维护读锁，再进入连接 monitor，保持全库锁序。 */
    public <T> T query(String sql, Object[] args, ResultSetHandler<T> handler) throws SQLException {
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter();
             DatabaseOperationGuard.Access access = operationGuard.read()) {
            return queryLocked(sql, args, handler);
        }
    }
    /** 在连接 monitor 内完成完整语句及结果消费；外层已经持有操作保护。 */
    private synchronized <T> T queryLocked(String sql, Object[] args, ResultSetHandler<T> handler) throws SQLException {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                return handler.handle(rs);
            }
        }
    }

    /**
     * 关闭连接
     */
    public void close() {
        operationGuard.beginResourceClose();
        if (!operationGuard.awaitIdle(Long.MAX_VALUE))
            throw new IllegalStateException("database still has active operations");
        synchronized (this) {
            if (connection != null) {
                try { connection.close(); }
                catch (SQLException failure) { throw new IllegalStateException("database close failed", failure); }
                connection = null;
            }
        }
    }
    /**
     * 是否已关闭
     */
    public synchronized boolean isClosed() {
        try {
            return connection == null || connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * 获取数据库文件大小（字节，仅主文件）
     */
    public synchronized long getDbSizeBytes() {
        if (dbPath == null) {
            return 0L;
        }
        try {
            java.io.File f = new java.io.File(dbPath);
            if (f.exists()) {
                return f.length();
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    /**
     * 获取数据库相关文件当前长度之和（字节）
     * <p>容量预算的度量口径：主数据库、存在的 -wal、-shm 以及存在的回滚日志
     * (-journal) 文件。它是可移植的文件长度口径，不宣称等于文件系统分配簇占用</p>
     */
    public synchronized long getDbTotalSizeBytes() {
        if (dbPath == null) {
            return 0L;
        }
        long total = 0L;
        // 主文件 + WAL/共享内存/回滚日志相关文件
        String[] suffixes = {"", "-wal", "-shm", "-journal"};
        for (String suffix : suffixes) {
            try {
                java.io.File f = new java.io.File(dbPath + suffix);
                if (f.exists()) {
                    total += f.length();
                }
            } catch (Throwable ignored) {
                // 单个文件不可读不影响其余度量
            }
        }
        return total;
    }

    // ===== 内部方法 =====

    private Connection openConnection(String path) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        String url = "jdbc:sqlite:" + path;
        Connection conn = DriverManager.getConnection(url);
        // 开启 WAL 模式
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
        return conn;
    }

    private void initSchema() throws SQLException {
        ensureOpen();
        try (Statement stmt = connection.createStatement()) {
            // 日志表
            stmt.execute(LogTableSchema.CREATE_LOG_TABLE);
            for (String indexSql : LogTableSchema.CREATE_LOG_INDEXES) {
                stmt.execute(indexSql);
            }
            // 异常表
            stmt.execute(LogTableSchema.CREATE_EXCEPTION_TABLE);
            for (String indexSql : LogTableSchema.CREATE_EXCEPTION_INDEXES) {
                stmt.execute(indexSql);
            }
        }
    }

    private void ensureOpen() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("JdbcHelper is closed");
        }
    }
}
