package com.atian10.logrecord.desktop.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库迁移集合
 * <p>
 * 当前版本 v1，无迁移。后续版本新增字段/表时在此添加迁移方法。
 * {@code DesktopLogInit} 初始化时会检查版本并按需调用迁移。
 * </p>
 */
public final class JdbcMigrations {

    private JdbcMigrations() {
        // 工具类禁止实例化
    }

    /**
     * 执行所有迁移到目标版本
     * <p>
     * 当前 v1 无迁移，直接返回。后续版本递增时按顺序执行 ALTER TABLE。
     * </p>
     * @param connection 数据库连接
     * @param fromVersion 当前版本
     * @param toVersion 目标版本
     * @throws SQLException 迁移失败
     */
    public static void migrate(Connection connection, int fromVersion, int toVersion)
            throws SQLException {
        if (connection == null || fromVersion >= toVersion) {
            return;
        }
        // v1 无迁移，后续版本示例：
        // if (fromVersion < 2) {
        //     try (Statement stmt = connection.createStatement()) {
        //         stmt.execute("ALTER TABLE log_record ADD COLUMN new_field TEXT");
        //     }
        // }
    }

    /**
     * 获取用户版本 pragma
     * @param connection 数据库连接
     * @return 当前用户版本号
     * @throws SQLException 查询失败
     */
    public static int getUserVersion(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    /**
     * 设置用户版本 pragma
     * @param connection 数据库连接
     * @param version 版本号
     * @throws SQLException 设置失败
     */
    public static void setUserVersion(Connection connection, int version) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA user_version = " + version);
        }
    }
}
