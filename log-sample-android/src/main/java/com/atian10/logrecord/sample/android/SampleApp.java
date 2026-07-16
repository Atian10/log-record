package com.atian10.logrecord.sample.android;

import android.app.Application;
import android.util.Log;

import com.atian10.logrecord.android.AndroidLogInit;
import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.export.ExportEncoding;

/**
 * Android 示例 Application
 * <p>
 * 在 Application.onCreate 中初始化日志库，保证整个进程生命周期内可用。
 * </p>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>用 ApplicationContext 初始化，避免 Activity 泄漏</li>
 *   <li>Android 端开启控制台输出会自动用 LogcatStorage 装饰，输出到 Logcat</li>
 *   <li>WAL 模式由 Room 在 API 16+ 自动开启</li>
 *   <li>数据库位置：{@code context.getDatabasePath("log_record.db")}（内部存储，由 Room 管理）</li>
 * </ul>
 * </p>
 */
public class SampleApp extends Application {

    private static final String TAG = "SampleApp";

    @Override
    public void onCreate() {
        super.onCreate();
        initLogger();
    }

    /**
     * 初始化日志库
     * <p>
     * 用 LogConfig.Builder 构建配置，传入 AndroidLogInit.init。
     * AndroidLogInit 会自动注入 RoomStorage/RoomExceptionStorage，并根据 consoleEnabled 决定是否装饰 LogcatStorage。
     * </p>
     */
    private void initLogger() {
        LogConfig.Builder builder = LogConfig.builder()
                .consoleEnabled(true)              // 开启 Logcat 输出（自动用 LogcatStorage 装饰）
                .captureMethodLine(true)           // 开启方法名/行号捕获（便于排查）
                .versionTag("android-sample-1.0.0")
                .queueCapacity(2048)
                .batchSize(100)
                .batchIntervalMillis(1000L)
                .exportEncoding(ExportEncoding.UTF_8)
                // 日志清理策略（默认关闭，这里演示开启）
                .cleanPolicy(CleanPolicy.builder()
                        .enable(true)
                        .keepDays(7)               // 保留 7 天
                        .maxDbSizeMB(100)
                        .maxRecordCount(50_000)
                        .cleanIntervalHours(24)
                        .build())
                // 异常表独立清理策略（异常保留更久）
                .exceptionCleanPolicy(CleanPolicy.builder()
                        .enable(true)
                        .keepDays(30)
                        .build());

        try {
            AndroidLogInit.init(this, builder);
            Log.i(TAG, "日志库初始化完成，状态: " + LogManager.get().getStatus());
        } catch (Throwable t) {
            // 初始化失败应记录但不阻塞 Application 启动
            Log.e(TAG, "日志库初始化失败", t);
        }
    }
}
