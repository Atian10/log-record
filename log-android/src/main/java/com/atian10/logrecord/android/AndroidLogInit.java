package com.atian10.logrecord.android;

import android.content.Context;

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
 * LogManager 初始化。业务方调用 {@link #init(Context, LogConfig)} 完成接入。
 * </p>
 * <p>
 * 数据库位置：{@code context.getDatabasePath("log_record.db")}，由 Room 自动管理。
 * WAL 模式：Room 默认在 API 16+ 开启 writeAheadLogging，无需额外配置。
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

    private static volatile LogDatabase database;

    private AndroidLogInit() {
        // 工具类禁止实例化
    }

    /**
     * 初始化日志库（Android 平台）
     * <p>
     * 内部完成：
     * <ol>
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
     * @throws IllegalStateException 重复初始化时抛出
     */
    public static LogManager init(Context context, LogConfig.Builder configBuilder) {
        if (context == null) {
            throw new NullPointerException("context == null");
        }
        if (configBuilder == null) {
            throw new NullPointerException("configBuilder == null");
        }
        Context appContext = context.getApplicationContext();

        // 1. 构建数据库
        LogDatabase db = buildDatabase(appContext);
        database = db;

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

        // 4. 重建最终配置：storage 用装饰后的，consoleEnabled 强制 false
        LogConfig finalConfig = LogConfig.builderFrom(tempConfig)
                .storage(effectiveStorage)
                .exceptionStorage(exceptionStorage)
                .consoleEnabled(false)  // Logcat 输出由装饰器处理，避免 System.out 重复
                .build();

        return LogManager.init(finalConfig);
    }

    /**
     * 获取已构建的 LogDatabase 实例
     * @return 数据库实例；未初始化时返回 null
     */
    public static LogDatabase getDatabase() {
        return database;
    }

    /**
     * 关闭日志库并释放数据库资源
     */
    public static void shutdown() {
        if (LogManager.isInitialized()) {
            LogManager.get().shutdown();
        }
        if (database != null) {
            try {
                database.close();
            } catch (Throwable ignored) {
            }
            database = null;
        }
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
        } catch (Throwable ignored) {
            // pragma 失败不阻塞初始化
        }
        return db;
    }
}
