package com.atian10.logrecord.desktop.jdbc;

/**
 * 数据库表结构定义
 * <p>
 * 集中管理建表 SQL，严格对齐功能文档第十五章数据库表结构。
 * v1 初始版本，包含 log_record 和 exception_table 两张表及对应索引。
 * </p>
 */
public final class LogTableSchema {

    private LogTableSchema() {
        // 工具类禁止实例化
    }

    /** 数据库当前版本 */
    public static final int DB_VERSION = 1;

    // ===== log_record 表 =====

    /** 创建日志主表 */
    public static final String CREATE_LOG_TABLE =
            "CREATE TABLE IF NOT EXISTS log_record ("
                    + "id              INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "timestamp       INTEGER NOT NULL,"
                    + "level           INTEGER NOT NULL,"
                    + "type            TEXT NOT NULL,"
                    + "tag             TEXT NOT NULL,"
                    + "thread_name     TEXT,"
                    + "thread_id       INTEGER,"
                    + "method_name     TEXT,"
                    + "line_number     INTEGER,"
                    + "message         TEXT,"
                    + "user_fields     TEXT,"
                    + "version_tag     TEXT,"
                    + "has_exception   INTEGER DEFAULT 0"
                    + ")";

    /** 日志表索引 */
    public static final String[] CREATE_LOG_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_log_timestamp ON log_record(timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_log_level ON log_record(level)",
            "CREATE INDEX IF NOT EXISTS idx_log_type ON log_record(type)",
            "CREATE INDEX IF NOT EXISTS idx_log_tag ON log_record(tag)",
            "CREATE INDEX IF NOT EXISTS idx_log_version_tag ON log_record(version_tag)"
    };

    // ===== exception_table 表 =====

    /** 创建异常表（独立，无外键） */
    public static final String CREATE_EXCEPTION_TABLE =
            "CREATE TABLE IF NOT EXISTS exception_table ("
                    + "id              INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "timestamp       INTEGER NOT NULL,"
                    + "exception_class TEXT,"
                    + "exception_msg   TEXT,"
                    + "stack_trace     TEXT,"
                    + "log_tag         TEXT"
                    + ")";

    /** 异常表索引 */
    public static final String[] CREATE_EXCEPTION_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_exception_timestamp ON exception_table(timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_exception_log_tag ON exception_table(log_tag)",
            "CREATE INDEX IF NOT EXISTS idx_exception_class ON exception_table(exception_class)"
    };

    // ===== 插入语句 =====

    /** 插入日志（不含 id，自增） */
    public static final String INSERT_LOG =
            "INSERT INTO log_record (timestamp, level, type, tag, thread_name, thread_id, "
                    + "method_name, line_number, message, user_fields, version_tag, has_exception) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** 插入异常（不含 id，自增） */
    public static final String INSERT_EXCEPTION =
            "INSERT INTO exception_table (timestamp, exception_class, exception_msg, "
                    + "stack_trace, log_tag) VALUES (?, ?, ?, ?, ?)";
}
