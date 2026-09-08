package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.engine.ShutdownResult;
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
 * <b>资源归属（ISSUE-10 修复）</b>：初始化先创建局部候选 JdbcHelper，核心初始化及
 * 全部必要步骤成功后才发布"LogManager + helper"所有者对；任一步骤失败仅释放候选资源，
 * 不影响已发布的旧所有者。重复初始化保持抛出 IllegalStateException，失败的第二次
 * 初始化不会替换静态 helper。关闭钩子绑定具体所有者，不会经可变静态字段关闭
 * 后来创建的实例。
 * </p>
 * <p>
 * <b>关闭时机</b>：{@link #shutdown()} 等待 LogManager 完成排空后才关闭 helper；
 * 超时未终止时由后台守护线程等待其完全终止后再关闭，避免在途写入被连接关闭中断。
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

    /** 关闭时等待排空的默认时限（毫秒） */
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5000L;

    /**
     * 已发布的资源所有者（LogManager 与其独占的 JdbcHelper 成对发布与释放）
     */
    private static final class HelperOwner {
        /** 拥有该 helper 的 LogManager 实例 */
        final LogManager manager;
        /** 该实例独占的数据库连接助手 */
        final JdbcHelper helper;

        HelperOwner(LogManager manager, JdbcHelper helper) {
            this.manager = manager;
            this.helper = helper;
        }
    }

    /** 当前发布的所有者；成功初始化前保持不变，关闭后置 null */
    private static volatile HelperOwner owner;

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
     *   <li>构建局部候选 JdbcHelper（含 WAL 模式 + 建表），成功前不发布任何静态资源</li>
     *   <li>执行数据库迁移（如需）</li>
     *   <li>构建 JdbcStorage（若 consoleEnabled=true，用 ConsoleStorage 装饰）</li>
     *   <li>构建 JdbcExceptionStorage</li>
     *   <li>用平台适配的 storage 替换原 config 中的 storage/exceptionStorage</li>
     *   <li>调用 LogManager.init；成功后才发布"实例 + helper"所有者对</li>
     *   <li>若 registerShutdownHook=true，注册绑定该所有者的 JVM ShutdownHook</li>
     * </ol>
     * 任一步骤失败：关闭候选 helper 并抛出原异常，既有所有者不受影响。
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

        // 1. 构建局部候选 helper（含 WAL + 建表）；失败直接抛出，未发布任何资源
        JdbcHelper candidate;
        try {
            candidate = new JdbcHelper(dbPath);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("JdbcHelper init failed: " + e.getMessage(), e);
        }

        try {
            // 2. 执行迁移（v1 无迁移，未来版本递增时生效）
            try {
                java.sql.Connection conn = candidate.getConnection();
                int currentVersion = JdbcMigrations.getUserVersion(conn);
                if (currentVersion < LogTableSchema.DB_VERSION) {
                    JdbcMigrations.migrate(conn, currentVersion, LogTableSchema.DB_VERSION);
                    JdbcMigrations.setUserVersion(conn, LogTableSchema.DB_VERSION);
                }
            } catch (java.sql.SQLException ignored) {
                // 迁移失败不阻塞（v1 无迁移，此分支不会进入）
            }

            // 3. 构建 storage
            JdbcStorage jdbcStorage = new JdbcStorage(candidate);
            JdbcExceptionStorage exceptionStorage = new JdbcExceptionStorage(candidate);

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

            // 6. 重建最终配置：consoleEnabled 强制 false（控制台输出由装饰器处理），
            //    并注入全库容量协调器（B4：一个数据库一个协调器，与两存储共享 helper）
            JdbcCapacityCoordinator coordinator = new JdbcCapacityCoordinator(
                    candidate, jdbcStorage, exceptionStorage);
            LogConfig finalConfig = LogConfig.builderFrom(tempConfig)
                    .storage(effectiveStorage)
                    .exceptionStorage(exceptionStorage)
                    .consoleEnabled(false)
                    .capacityCoordinator(coordinator)
                    .build();

            // 7. 核心初始化；成功后才发布所有者对（ISSUE-10：失败不得覆盖既有静态 helper）
            LogManager manager = LogManager.init(finalConfig);
            HelperOwner published = new HelperOwner(manager, candidate);
            owner = published;

            // 8. 可选注册 JVM ShutdownHook（绑定本次发布的所有者，不经可变静态字段）
            if (registerShutdownHook) {
                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> shutdownOwner(published), "log-record-shutdown-hook"));
            }

            return manager;
        } catch (Throwable t) {
            // 失败仅释放候选资源；已发布所有者不受影响
            try {
                candidate.close();
            } catch (Throwable closeFailure) {
                t.addSuppressed(closeFailure);
            }
            throw t;
        }
    }

    /**
     * 获取已构建的 JdbcHelper 实例
     * @return 当前所有者的 helper；未初始化或已关闭时返回 null
     */
    public static JdbcHelper getHelper() {
        HelperOwner current = owner;
        return current != null ? current.helper : null;
    }

    /**
     * 关闭日志库并释放数据库连接（关闭当前发布的所有者）
     * <p>等待排空完成后才关闭 helper；超时由后台线程在完全终止后关闭</p>
     */
    public static void shutdown() {
        shutdownOwner(owner);
    }

    /**
     * 关闭指定所有者：等待 LogManager 排空并退出后再关闭其 helper
     * <p>
     * 仅当该所有者仍是当前发布者时清除静态引用，避免关闭后来创建的实例。
     * </p>
     */
    private static void shutdownOwner(HelperOwner target) {
        if (target == null) {
            return;
        }
        if (owner == target) {
            owner = null;
        }
        ShutdownResult result = target.manager.shutdown(DEFAULT_SHUTDOWN_TIMEOUT_MILLIS);
        if (result.isFullyTerminated()) {
            target.helper.close();
            return;
        }
        // 排空超时：后台等待完全终止后再关闭 helper，不中断在途写入
        Thread closer = new Thread(() -> {
            while (!target.manager.isTerminated()) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            target.helper.close();
        }, "log-record-helper-closer");
        closer.setDaemon(true);
        closer.start();
    }
}
