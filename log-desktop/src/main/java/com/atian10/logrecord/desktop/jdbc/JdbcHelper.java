package com.atian10.logrecord.desktop.jdbc;

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
 * 所有写操作通过 {@link #executeUpdate(String, Object[])} 执行，使用 PreparedStatement 防 SQL 注入。
 * 连接关闭由 {@link #close()} 释放（通常在 LogManager.shutdown 时调用）。
 * </p>
 * <p>
 * 线程安全：JDBC Connection 本身非线程安全，本类所有方法 synchronized 保证串行访问。
 * 异步引擎为单工作线程写，查询由业务线程发起，synchronized 足够保证安全。
 * </p>
 */
public final class JdbcHelper {

    private final String dbPath;
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
     * <p>调用方不应关闭此 Connection，由 {@link #close()} 统一管理</p>
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
    public synchronized int executeUpdate(String sql, Object[] args) throws SQLException {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
            }
            return ps.executeUpdate();
        }
    }

    /**
     * 批量插入（事务）
     * @param sql INSERT 语句
     * @param batchArgs 批量参数（每行一组参数）
     * @return 插入总行数
     * @throws SQLException 执行失败
     */
    public synchronized int executeBatch(String sql, Object[][] batchArgs) throws SQLException {
        ensureOpen();
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (batchArgs != null) {
                for (Object[] args : batchArgs) {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            ps.setObject(i + 1, args[i]);
                        }
                    }
                    ps.addBatch();
                }
            }
            int[] counts = ps.executeBatch();
            connection.commit();
            int total = 0;
            for (int c : counts) {
                if (c > 0) {
                    total += c;
                }
            }
            return total;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * 查询单值（如 COUNT(*)）
     * @param sql 查询语句
     * @param args 参数
     * @return 首行首列的 long 值
     * @throws SQLException 执行失败
     */
    public synchronized long queryLong(String sql, Object[] args) throws SQLException {
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
    public synchronized <T> T query(String sql, Object[] args, ResultSetHandler<T> handler)
            throws SQLException {
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
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
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
     * 获取数据库文件大小（字节）
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
