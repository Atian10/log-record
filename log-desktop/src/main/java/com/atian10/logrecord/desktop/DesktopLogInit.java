package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;
import com.atian10.logrecord.desktop.jdbc.JdbcMigrations;
import com.atian10.logrecord.desktop.jdbc.LogTableSchema;

/**
 * 桌面/服务器平台初始化入口
 * <p>
 * 封装 JdbcHelper 构建 + JdbcStorage/JdbcExceptionStorage/ConsoleStorage 装配 +
 * LogManager 初始化。业务方调用 {@link #init(String, LogConfig.Builder)} 完成接入。
 * </p>
 * <p>
 * 数据库位置：业务方通过 dbPath 参数指定（如 "/var/log/app/log_record.db"）。
 * WAL 模式：JdbcHelper 初始化时自动开启。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * LogConfig config = LogConfig.builder()
 *     .consoleEnabled(true)
 *     .captureMethodLine(false)
 *     .versionTag("1.0.0")
 *     .cleanPolicy(CleanPolicy.builder().enable(true).keepDays(7).build())
 *     .build();
 * DesktopLogInit.init("/var/log/app/log_record.db", config);
 * LogManager.get().i("Main", "app started");
 * </pre>
 * </p>
 */
public final class DesktopLogInit {

    private static volatile JdbcHelper helper;

    private DesktopLogInit() {
        // 工具类禁止实例化
    }

    /**
     * 初始化日志库（桌面/服务器平台）
     * <p>等价于 {@code init(dbPath, configBuilder, false)}，不注册 ShutdownHook</p>
     * @param dbPath 数据库文件路径
     * @param configBuilder 配置构建器（storage/exceptionStorage 字段会被本方法覆盖）
     * @return LogManager 实例
     * @throws IllegalStateException 重复初始化或数据库初始化失败时抛出
     */
    public static LogManager init(String dbPath, LogConfig.Builder configBuilder) {
        return init(dbPath, configBuilder, false);
    }

    /**
     * 初始化日志库（桌面/服务器平台）
     * <p>
     * 内部完成：
     * <ol>
     *   <li>构建 JdbcHelper（含 WAL 模式 + 建表）</li>
     *   <li>执行数据库迁移（如需）</li>
     *   <li>构建 JdbcStorage（若 consoleEnabled=true，用 ConsoleStorage 装饰）</li>
     *   <li>构建 JdbcExceptionStorage</li>
     *   <li>用平台适配的 storage 替换原 config 中的 storage/exceptionStorage</li>
     *   <li>调用 LogManager.init</li>
     *   <li>若 registerShutdownHook=true，注册 JVM ShutdownHook 在进程退出时调用 shutdown()</li>
     * </ol>
     * </p>
     * @param dbPath 数据库文件路径
     * @param configBuilder 配置构建器（storage/exceptionStorage 字段会被本方法覆盖）
     * @param registerShutdownHook 是否注册 JVM ShutdownHook，进程退出时自动关闭日志库
     * @return LogManager 实例
     * @throws IllegalStateException 重复初始化或数据库初始化失败时抛出
     */
    public static LogManager init(String dbPath, LogConfig.Builder configBuilder,
                                  boolean registerShutdownHook) {
        if (dbPath == null || dbPath.isEmpty()) {
            throw new IllegalArgumentException("dbPath is null or empty");
        }
        if (configBuilder == null) {
            throw new NullPointerException("configBuilder == null");
        }

        // 1. 构建 JdbcHelper（含 WAL + 建表）
        JdbcHelper jdbcHelper;
        try {
            jdbcHelper = new JdbcHelper(dbPath);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("JdbcHelper init failed: " + e.getMessage(), e);
        }
        helper = jdbcHelper;

        // 2. 执行迁移（v1 无迁移，未来版本递增时生效）
        try {
            java.sql.Connection conn = jdbcHelper.getConnection();
            int currentVersion = JdbcMigrations.getUserVersion(conn);
            if (currentVersion < LogTableSchema.DB_VERSION) {
                JdbcMigrations.migrate(conn, currentVersion, LogTableSchema.DB_VERSION);
                JdbcMigrations.setUserVersion(conn, LogTableSchema.DB_VERSION);
            }
        } catch (java.sql.SQLException ignored) {
            // 迁移失败不阻塞（v1 无迁移，此分支不会进入）
        }

        // 3. 构建 storage
        JdbcStorage jdbcStorage = new JdbcStorage(jdbcHelper);
        JdbcExceptionStorage exceptionStorage = new JdbcExceptionStorage(jdbcHelper);

        // 4. 临时构建配置读取 consoleEnabled
        LogConfig tempConfig = configBuilder
                .storage(jdbcStorage)
                .exceptionStorage(exceptionStorage)
                .build();
        boolean consoleEnabled = tempConfig.isConsoleEnabled();

        // 5. 根据 consoleEnabled 决定是否装饰
        IStorage effectiveStorage = consoleEnabled
                ? new ConsoleStorage(jdbcStorage, true)
                : jdbcStorage;

        // 6. 重建最终配置：consoleEnabled 强制 false（控制台输出由装饰器处理）
        LogConfig finalConfig = LogConfig.builderFrom(tempConfig)
                .storage(effectiveStorage)
                .exceptionStorage(exceptionStorage)
                .consoleEnabled(false)
                .build();

        LogManager manager = LogManager.init(finalConfig);

        // 7. 可选注册 JVM ShutdownHook
        if (registerShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(DesktopLogInit::shutdown,
                    "log-record-shutdown-hook"));
        }

        return manager;
    }

    /**
     * 获取已构建的 JdbcHelper 实例
     * @return helper 实例；未初始化时返回 null
     */
    public static JdbcHelper getHelper() {
        return helper;
    }

    /**
     * 关闭日志库并释放数据库连接
     */
    public static void shutdown() {
        if (LogManager.isInitialized()) {
            LogManager.get().shutdown();
        }
        if (helper != null) {
            helper.close();
            helper = null;
        }
    }
}
