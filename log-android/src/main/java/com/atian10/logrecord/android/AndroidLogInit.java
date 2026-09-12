package com.atian10.logrecord.android;

import android.content.Context;
import android.util.Log;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.android.room.LogDatabase;
import com.atian10.logrecord.android.room.LogDatabaseMigrations;

/**
 * Android 平台初始化入口
 * <p>
 * 封装 LogDatabase 构建 + RoomStorage/RoomExceptionStorage/LogcatStorage 装配 +
 * LogManager 初始化。业务方调用 {@link #init(Context, LogConfig.Builder)} 完成接入。
 * </p>
 * <p>
 * 数据库位置：{@code context.getDatabasePath("log_record.db")}，由 Room 自动管理。
 * WAL 模式：Room 默认在 API 16+ 开启 writeAheadLogging，无需额外配置。
 * </p>
 * <p>
 * <b>多进程限制</b>：本库不支持多进程并发访问同一 SQLite 文件。若业务方在多进程
 * （如 {@code :remote} process）中调用 init，会构建多个 RoomDatabase 实例同时打开
 * 同一文件，可能导致数据库锁竞争或损坏。多进程场景请使用 ContentProvider 中转或独立 DB。
 * </p>
 * <p>
 * <b>debug/release 区分</b>：本库关闭了 BuildConfig 生成，建议业务方通过
 * {@code LogConfig.versionTag} 主动标识构建类型（如 "1.0.0-debug" / "1.0.0-release"）。
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
 * AndroidLogInit.init(context, config);
 * LogManager.get().i("MainActivity", "app started");
 * </pre>
 * </p>
 */
public final class AndroidLogInit {

    /** 数据库文件名 */
    private static final String DB_NAME = "log_record.db";

    /** manager 与 Room 成对发布，旧实例关闭不会影响新实例。 */
    private static final java.util.concurrent.atomic.AtomicReference<DatabaseOwner> owner =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final class DatabaseOwner {
        /** 本次初始化的不可变资源对。 */
        final LogManager manager;
        final LogDatabase database;
        /** 仅在核心初始化全部成功后构造并发布。 */
        DatabaseOwner(LogManager manager, LogDatabase database) { this.manager = manager; this.database = database; }
    }

    private AndroidLogInit() {
        // 工具类禁止实例化
    }

    /**
     * 初始化日志库（Android 平台）
     * <p>
     * 内部完成：
     * <ol>
     *   <li>若已初始化，直接返回现有实例（防重复调用导致 DB 连接泄漏）</li>
     *   <li>构建 LogDatabase（含迁移注册）</li>
     *   <li>构建 RoomStorage（若 consoleEnabled=true，用 LogcatStorage 装饰）</li>
     *   <li>构建 RoomExceptionStorage</li>
     *   <li>用平台适配的 storage 替换原 config 中的 storage/exceptionStorage</li>
     *   <li>调用 LogManager.init</li>
     * </ol>
     * </p>
     * @param context Android Context（建议用 ApplicationContext 避免泄漏）
     * @param configBuilder 配置构建器（storage/exceptionStorage 字段会被本方法覆盖）
     * @return LogManager 实例
     */
    public static synchronized LogManager init(Context context, LogConfig.Builder configBuilder) {
        if (context == null) {
            throw new NullPointerException("context == null");
        }
        if (configBuilder == null) {
            throw new NullPointerException("configBuilder == null");
        }
        // 防重复调用：已初始化直接返回现有实例，避免重复构建 DB 导致连接泄漏
        if (LogManager.isInitialized()) {
            LogManager current = LogManager.get();
            if (current.isClosing()) throw new IllegalStateException("previous LogManager is closing");
            return current;
        }
        Context appContext = context.getApplicationContext();

        // 1. 构建数据库
        LogDatabase db = buildDatabase(appContext);
        // 候选数据库成功前不写静态 owner，失败只释放本候选。
        try {

        // 2. 先从 builder 临时构建一次配置，读取 consoleEnabled 等动态配置项
        // （storage/exceptionStorage 会被覆盖，故先放占位）
        // 为避免占位 storage 校验失败，先构造真实 storage
        RoomStorage roomStorage = new RoomStorage(db);
        RoomExceptionStorage exceptionStorage = new RoomExceptionStorage(db);

        // 临时配置读取 consoleEnabled（需 build 后读取）
        LogConfig tempConfig = configBuilder
                .storage(roomStorage)
                .exceptionStorage(exceptionStorage)
                .build();
        boolean consoleEnabled = tempConfig.isConsoleEnabled();

        // 3. 根据 consoleEnabled 决定是否用 LogcatStorage 装饰
        // 注意：LogManager 自身的 consoleEnabled 会被设为 false，避免 System.out 重复输出
        IStorage effectiveStorage = consoleEnabled
                ? new LogcatStorage(roomStorage, true)
                : roomStorage;

        // 4. 重建最终配置：storage 用装饰后的，consoleEnabled 强制 false，
        //    并注入全库容量协调器（B4：一个数据库一个协调器，与两存储共享 Room 实例）
        RoomCapacityCoordinator coordinator = new RoomCapacityCoordinator(
                db, roomStorage, exceptionStorage);
        LogConfig finalConfig = LogConfig.builderFrom(tempConfig)
                .storage(effectiveStorage)
                .exceptionStorage(exceptionStorage)
                .consoleEnabled(false)  // Logcat 输出由装饰器处理，避免 System.out 重复输出
                .capacityCoordinator(coordinator)
                .databaseOwner(db.getOperationGuard(), db::close)
                .build();

        LogManager manager = LogManager.init(finalConfig);
        owner.set(new DatabaseOwner(manager, db));
        return manager;
        } catch (Throwable failure) {
            try { db.close(); } catch (Throwable closeError) { failure.addSuppressed(closeError); }
            throw failure;
        }
    }

    /**
     * 获取已构建的 LogDatabase 实例
     * @return 数据库实例；未初始化时返回 null
     */
    public static LogDatabase getDatabase() {
        DatabaseOwner current = owner.get();
        return current != null && !current.manager.isTerminated() ? current.database : null;
    }

    /**
     * 关闭日志库并释放数据库资源
     * <p>
     * 等待 LogManager 完成排空后才关闭数据库；超时未终止时由后台守护线程
     * 等待其完全终止后再关闭，避免在途写入被数据库关闭中断。
     * </p>
     */
    public static void shutdown() {
        // 捕获不可变所有者，资源关闭由该 manager 的唯一收尾者负责。
        DatabaseOwner current = owner.get();
        if (current == null) return;
        current.manager.shutdown();
        if (current.manager.isTerminated()) owner.compareAndSet(current, null);
    }
    /**
     * 构建数据库
     */
    private static LogDatabase buildDatabase(Context appContext) {
        LogDatabase db = Room.databaseBuilder(appContext, LogDatabase.class, DB_NAME)
                .addMigrations(LogDatabaseMigrations.getAll())
                // 开启 WAL 模式（API 16+ 默认开启，显式设置保证一致性）
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build();
        // 触发数据库实际创建（可选，确保首次访问不阻塞）
        try {
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();
            // 执行 WAL pragma（Room 默认已开启，此处显式设置保证一致性）
            sqlite.execSQL("PRAGMA journal_mode=WAL");
            sqlite.execSQL("PRAGMA synchronous=NORMAL");
        } catch (Throwable t) {
            // pragma 失败不阻塞初始化，但输出到 logcat 便于业务方诊断
            Log.w("LogRecord", "PRAGMA setup failed (non-blocking): " + t.getMessage(), t);
        }
        return db;
    }
}
