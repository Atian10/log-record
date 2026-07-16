package com.atian10.logrecord.sample.desktop;

import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.engine.LogStatus;
import com.atian10.logrecord.core.export.ExportCallback;
import com.atian10.logrecord.core.export.ExportEncoding;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.StandardLogType;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.desktop.DesktopLogInit;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务器平台（Windows/Linux 服务器）使用示例
 * <p>
 * 与桌面示例的核心差异：
 * <ul>
 *   <li>数据库路径使用 Linux 风格的 /var/log 目录（Windows 服务器可改成 D:/logs）</li>
 *   <li>开启多维度清理策略（天数 + 容量 + 数量组合）</li>
 *   <li>演示长期运行场景：定时打日志 + 定时巡检 + 优雅关闭（注册 ShutdownHook）</li>
 *   <li>异常表清理策略与日志表独立配置</li>
 *   <li>业务上报示例：定时把 ERROR 日志导出后由业务自行上传</li>
 * </ul>
 * </p>
 * <p>
 * 运行方式：直接执行 main 方法，运行 30 秒后自动退出；也可通过 kill / Ctrl+C 触发 ShutdownHook 优雅关闭。
 * </p>
 */
public final class ServerSample {

    /** 默认数据库文件路径（Linux 服务器标准路径，生产环境推荐） */
    private static final String DEFAULT_DB_PATH = "/var/log/log-record/server_log.db";

    /** 默认导出目录 */
    private static final String DEFAULT_EXPORT_DIR = "/var/log/log-record/export";

    private ServerSample() {
        // 示例类禁止实例化
    }

    public static void main(String[] args) {
        // 数据库路径优先级：
        //   1) 启动参数 --db.path=/path/to.db
        //   2) 环境变量 LOG_RECORD_DB_PATH
        //   3) 默认 /var/log/log-record/server_log.db（Linux 服务器场景；Windows 服务器请通过启动参数指定如 D:/logs/server_log.db）
        String dbPath = System.getProperty("db.path");
        if (dbPath == null || dbPath.isEmpty()) {
            dbPath = System.getenv("LOG_RECORD_DB_PATH");
        }
        if (dbPath == null || dbPath.isEmpty()) {
            dbPath = DEFAULT_DB_PATH;
        }

        // 导出目录优先级同上
        String exportDirRaw = System.getProperty("export.dir");
        if (exportDirRaw == null || exportDirRaw.isEmpty()) {
            exportDirRaw = System.getenv("LOG_RECORD_EXPORT_DIR");
        }
        if (exportDirRaw == null || exportDirRaw.isEmpty()) {
            exportDirRaw = DEFAULT_EXPORT_DIR;
        }
        final String exportDir = exportDirRaw;

        // 1. 准备目录（生产环境应预先由运维创建并设置权限）
        File dbFile = new File(dbPath);
        if (dbFile.getParentFile() != null) {
            dbFile.getParentFile().mkdirs();
        }
        new File(exportDir).mkdirs();

        // 2. 初始化日志库（服务器场景配置）
        LogConfig.Builder builder = LogConfig.builder()
                .consoleEnabled(true)                      // 服务器输出到 stdout/stderr（ConsoleStorage 自动分流）
                .captureMethodLine(true)                   // 服务器排查问题需要方法行号
                .versionTag("server-2.1.0")
                .queueCapacity(8192)                       // 服务器日志量大，队列调大
                .batchSize(200)                            // 批量写入阈值调大
                .batchIntervalMillis(500L)                 // 周期缩短到 0.5 秒
                .exportEncoding(ExportEncoding.UTF_8)
                // 日志表清理：多维度组合，任一触发即清理
                .cleanPolicy(CleanPolicy.builder()
                        .enable(true)
                        .keepDays(30)                      // 保留 30 天
                        .maxDbSizeMB(2048)                 // 数据库超过 2GB 触发清理
                        .maxRecordCount(5_000_000)         // 超过 500 万条触发清理
                        .cleanIntervalHours(6)             // 每 6 小时定时检查
                        .build())
                // 异常表清理：异常保留更久，便于回溯
                .exceptionCleanPolicy(CleanPolicy.builder()
                        .enable(true)
                        .keepDays(90)                      // 异常保留 90 天
                        .maxRecordCount(500_000)
                        .cleanIntervalHours(12)
                        .build());

        DesktopLogInit.init(dbPath, builder);
        LogManager logger = LogManager.get();
        System.out.println("[Server] 日志库初始化完成，状态: " + logger.getStatus());

        // 3. 注册 ShutdownHook（kill / Ctrl+C 时优雅关闭）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Server] 收到关闭信号，开始优雅关闭...");
            DesktopLogInit.shutdown();
            System.out.println("[Server] 已关闭日志库");
        }, "log-shutdown"));

        // 4. 启动定时任务：日志生产 + 巡检 + 业务上报
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
        // 4.1 每 200ms 模拟业务日志
        scheduler.scheduleAtFixedRate(
                () -> produceBusinessLog(logger), 0, 200, TimeUnit.MILLISECONDS);
        // 4.2 每 10 秒巡检一次库状态
        scheduler.scheduleAtFixedRate(
                () -> inspectStatus(logger), 5, 10, TimeUnit.SECONDS);
        // 4.3 每 60 秒业务上报（导出 ERROR 日志后由业务自行上传）
        scheduler.scheduleAtFixedRate(
                () -> reportErrorLogs(logger, exportDir), 10, 60, TimeUnit.SECONDS);

        // 5. 主线程运行 30 秒后退出（生产环境去掉这段，由容器/进程管理器控制生命周期）
        try {
            Thread.sleep(30_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // ShutdownHook 会负责关闭日志库；这里直接退出主线程
        System.exit(0);
    }

    // ===== 模拟业务日志生产 =====

    private static void produceBusinessLog(LogManager logger) {
        try {
            // 模拟 API 请求日志（带用户字段）
            Map<String, String> fields = new HashMap<>();
            fields.put("endpoint", "/api/v1/order/create");
            fields.put("method", "POST");
            fields.put("latencyMs", String.valueOf(20 + (int) (Math.random() * 200)));
            fields.put("statusCode", Math.random() > 0.05 ? "200" : "500");
            logger.log(LogLevel.INFO,
                    StandardLogType.NETWORK.code(),
                    "ApiGateway",
                    "收到创建订单请求",
                    fields);

            // 偶发错误
            if (Math.random() < 0.05) {
                try {
                    throw new RuntimeException("下游库存服务超时");
                } catch (RuntimeException e) {
                    logger.e("OrderService", "创建订单失败", e);
                }
            }

            // 数据库慢查询警告
            if (Math.random() < 0.1) {
                logger.log(LogLevel.WARN,
                        StandardLogType.DATABASE.code(),
                        "OrderDao",
                        "检测到慢查询，耗时 850ms");
            }
        } catch (Throwable t) {
            // 业务异常绝不能影响日志库的稳定性
            System.err.println("[Server] 业务日志生产异常: " + t.getMessage());
        }
    }

    // ===== 巡检 =====

    private static void inspectStatus(LogManager logger) {
        try {
            LogStatus status = logger.getStatus();
            long logCount = logger.getLogCount();
            long exceptionCount = logger.getExceptionCount();
            long dbSizeBytes = logger.getDbSizeBytes();
            System.out.println("[Server] 巡检: state=" + status.getState()
                    + ", logs=" + logCount
                    + ", exceptions=" + exceptionCount
                    + ", dbSize=" + (dbSizeBytes / 1024 / 1024) + "MB"
                    + ", warnings=" + status.getWarningCount());

            // 降级状态告警
            if (status.isDegraded() || status.isError()) {
                System.err.println("[Server] ⚠️ 日志库异常: " + status.getMessage());
            }
        } catch (Throwable t) {
            System.err.println("[Server] 巡检异常: " + t.getMessage());
        }
    }

    // ===== 业务上报（库不实现网络上报，业务方自行处理） =====

    private static void reportErrorLogs(LogManager logger, String exportDir) {
        try {
            // 取最近 60 秒的 ERROR 日志
            long now = System.currentTimeMillis();
            long from = now - 60_000L;
            LogStatistics stats = logger.statistics(LogQuery.builder()
                    .level(LogLevel.ERROR)
                    .fromTime(from)
                    .toTime(now)
                    .build());
            long errorCount = stats.getTotalCount();
            if (errorCount == 0) {
                return;
            }

            // 导出为 JSON，业务方读取文件后上传到日志中心
            String exportPath = exportDir + File.separator
                    + "error_" + now + ".json";
            logger.exportLogs(
                    LogQuery.builder()
                            .level(LogLevel.ERROR)
                            .fromTime(from)
                            .toTime(now)
                            .orderBy(OrderBy.ASC)
                            .build(),
                    ExportFormat.JSON,
                    exportPath,
                    new ExportCallback() {
                        @Override
                        public void onProgress(int exported, int total) {
                        }

                        @Override
                        public void onSuccess(String filePath, int totalCount) {
                            System.out.println("[Server] 业务上报：导出 "
                                    + totalCount + " 条 ERROR 日志到 " + filePath
                                    + "，业务方读取后上传到日志中心");
                            // uploadToLogCenter(filePath);
                        }

                        @Override
                        public void onFailure(Throwable error, int exportedCount) {
                            System.err.println("[Server] 业务上报导出失败: "
                                    + error.getMessage());
                        }
                    });
        } catch (Throwable t) {
            System.err.println("[Server] 业务上报异常: " + t.getMessage());
        }
    }
}
