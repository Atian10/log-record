package com.atian10.logrecord.sample.desktop;

import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.clean.CleanCallback;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.config.LogConfigUpdater;
import com.atian10.logrecord.core.engine.LogStatus;
import com.atian10.logrecord.core.export.ExportCallback;
import com.atian10.logrecord.core.export.ExportEncoding;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.model.StandardLogType;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.desktop.DesktopLogInit;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面平台（Windows/macOS/Linux 桌面）使用示例
 * <p>
 * 演示完整接入流程：
 * <ol>
 *   <li>初始化（LogConfig.Builder 链式配置 + DesktopLogInit.init）</li>
 *   <li>写入日志（便捷方法 + 携带异常 + 携带用户键值对）</li>
 *   <li>查询（条件查询 + 聚合统计 + 异常独立查询）</li>
 *   <li>导出（JSON/CSV/TXT 三种格式）</li>
 *   <li>运行时动态修改配置（LogConfigUpdater）</li>
 *   <li>清理（手动清理 + 按时间清理）</li>
 *   <li>关闭（shutdown）</li>
 * </ol>
 * </p>
 * <p>
 * 运行方式：直接执行 main 方法。数据库文件默认生成在系统临时目录。
 * </p>
 */
public final class DesktopSample {

    private DesktopSample() {
        // 示例类禁止实例化
    }

    public static void main(String[] args) {
        // 数据库文件路径：系统临时目录 + 子目录
        String dbDir = System.getProperty("java.io.tmpdir")
                + File.separator + "log-record-sample" + File.separator + "desktop";
        new File(dbDir).mkdirs();
        String dbPath = dbDir + File.separator + "log_record.db";

        // ===== 1. 初始化 =====
        // 注意：传入的是 LogConfig.Builder（不是 LogConfig），平台适配层会注入 storage/exceptionStorage
        LogConfig.Builder builder = LogConfig.builder()
                .consoleEnabled(true)              // 桌面端开启控制台输出（自动用 ConsoleStorage 装饰）
                .captureMethodLine(true)           // 开启方法名/行号捕获
                .versionTag("desktop-sample-1.0.0")
                .queueCapacity(2048)               // 异步队列容量
                .batchSize(100)                    // 批量写入阈值
                .batchIntervalMillis(1000L)        // 批量写入周期
                .exportEncoding(ExportEncoding.UTF_8)
                // 日志清理策略（默认关闭，这里演示开启）
                .cleanPolicy(CleanPolicy.builder()
                        .enable(true)
                        .keepDays(7)               // 保留最近 7 天
                        .maxDbSizeMB(100)          // 数据库超过 100MB 触发清理
                        .maxRecordCount(50000)     // 超过 5 万条触发清理
                        .cleanIntervalHours(24)    // 每 24 小时定时检查一次
                        .build())
                // 异常表独立清理策略（与日志表独立）
                .exceptionCleanPolicy(CleanPolicy.builder()
                        .enable(true)
                        .keepDays(30)              // 异常保留 30 天（比日志更久）
                        .build());

        DesktopLogInit.init(dbPath, builder);

        // 获取日志写入器（也可直接 LogManager.get()）
        LogManager logger = LogManager.get();
        System.out.println("==== 初始化完成，状态: " + logger.getStatus());

        try {
            // ===== 2. 写入日志 =====
            writeSampleLogs(logger);

            // ===== 3. 查询 =====
            querySample(logger);

            // ===== 4. 导出 =====
            exportSample(logger);

            // ===== 5. 动态修改配置 =====
            updateConfigSample(logger);

            // ===== 6. 清理 =====
            cleanSample(logger);

            // ===== 7. 状态查询 =====
            printStatus(logger);

            // 等待异步队列消费完成
            logger.flush();
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // ===== 8. 优雅关闭 =====
            // 先 flush 等待剩余日志写入，再关闭清理任务和引擎，最后释放 JDBC 连接
            DesktopLogInit.shutdown();
            System.out.println("==== 已关闭日志库");
        }
    }

    // ===== 写入示例 =====

    private static void writeSampleLogs(LogManager logger) {
        // 1) 基础便捷方法（type 默认 SYSTEM）
        logger.d("Bootstrap", "调试信息：启动中");
        logger.i("Bootstrap", "普通信息：初始化完成");
        logger.w("Bootstrap", "警告信息：配置项缺失，使用默认值");
        logger.e("Bootstrap", "错误信息：依赖服务无响应");
        logger.f("Bootstrap", "致命错误：核心组件崩溃");

        // 2) 携带异常的便捷方法（内部拆为两条独立记录）
        //    → 一条 ERROR 日志（has_exception=1）
        //    → 一条 exception_table 记录（log_tag="LoginService"）
        try {
            throw new IllegalStateException("用户会话已过期");
        } catch (IllegalStateException e) {
            logger.e("LoginService", "登录失败", e);
        }

        // 3) 携带用户键值对（业务字段 + 纯文本 message）
        Map<String, String> userFields = new HashMap<>();
        userFields.put("userId", "10086");
        userFields.put("ip", "192.168.1.100");
        userFields.put("errorCode", "AUTH_FAIL");
        userFields.put("retryCount", "3");
        logger.log(LogLevel.ERROR,
                StandardLogType.NETWORK.code(),
                "LoginService",
                "用户登录失败",
                userFields);

        // 4) 携带用户键值对 + 异常
        try {
            throw new NullPointerException("用户对象为空");
        } catch (NullPointerException e) {
            Map<String, String> fields = new HashMap<>();
            fields.put("userId", "10086");
            fields.put("action", "queryProfile");
            logger.log(LogLevel.ERROR,
                    StandardLogType.BUSINESS.code(),
                    "UserService",
                    "查询用户资料失败",
                    fields,
                    e);
        }

        // 5) 异常独立写入（不伴随日志）
        try {
            throw new RuntimeException("数据库连接超时");
        } catch (RuntimeException e) {
            logger.recordException(e, "DatabaseService");
        }

        // 6) 自定义日志类型（无需注册，直接传字符串）
        logger.log(LogLevel.INFO, "PAYMENT", "PaymentService", "订单支付成功");
        logger.log(LogLevel.INFO, "PAYMENT", "PaymentService", "退款流程启动");

        // 7) 业务上报示例（其他项目通过查询 API 取日志后自行上报）
        reportSample(logger);
    }

    /**
     * 业务项目上报示例
     * <p>
     * 业务方通过 query 取出指定条件的日志后，调用自有上报逻辑上传到服务端。
     * 库本身不实现网络上报。
     * </p>
     */
    private static void reportSample(LogManager logger) {
        long oneHourAgo = System.currentTimeMillis() - 3600_000L;
        List<LogRecord> errorLogs = logger.queryLogs(LogQuery.builder()
                .level(LogLevel.ERROR)
                .fromTime(oneHourAgo)
                .orderBy(OrderBy.ASC)
                .limit(100)
                .build());
        if (!errorLogs.isEmpty()) {
            // 业务方在此调用自有上传逻辑：uploadToServer(errorLogs);
            System.out.println("==== 业务上报：检测到 " + errorLogs.size() + " 条错误日志");
        }
    }

    // ===== 查询示例 =====

    private static void querySample(LogManager logger) {
        long now = System.currentTimeMillis();
        long oneDayAgo = now - 86_400_000L;

        // 1) 组合条件查询：ERROR + NETWORK + 时间范围 + 用户字段
        List<LogRecord> records = logger.queryLogs(LogQuery.builder()
                .level(LogLevel.ERROR)
                .type(StandardLogType.NETWORK.code())
                .tag("LoginService")
                .keyword("失败")
                .fromTime(oneDayAgo)
                .toTime(now)
                .userField("errorCode", "AUTH_FAIL")  // 按用户键值对查询
                .versionTag("desktop-sample-1.0.0")
                .orderBy(OrderBy.DESC)
                .offset(0)
                .limit(50)
                .build());
        System.out.println("==== 条件查询命中 " + records.size() + " 条");
        for (LogRecord r : records) {
            System.out.println("  " + r.getTimestamp()
                    + " [" + r.getLevel() + "] "
                    + r.getTag() + " : " + r.getMessage());
        }

        // 2) 聚合统计（按级别/类型/Tag 维度）
        LogStatistics stats = logger.statistics(LogQuery.builder()
                .fromTime(oneDayAgo)
                .toTime(now)
                .build());
        System.out.println("==== 聚合统计 总数: " + stats.getTotalCount());
        System.out.println("  按级别: " + stats.getCountByLevel());
        System.out.println("  按类型: " + stats.getCountByType());
        System.out.println("  按Tag: " + stats.getCountByTag());

        // 3) 异常独立查询（与日志表完全独立）
        List<com.atian10.logrecord.core.model.ExceptionRecord> exceptions =
                logger.queryExceptions(ExceptionQuery.builder()
                        .tag("LoginService")
                        .fromTime(oneDayAgo)
                        .toTime(now)
                        .exceptionClass("IllegalStateException")
                        .orderBy(OrderBy.DESC)
                        .limit(20)
                        .build());
        System.out.println("==== 异常查询命中 " + exceptions.size() + " 条");
    }

    // ===== 导出示例 =====

    private static void exportSample(LogManager logger) throws InterruptedException {
        String exportDir = System.getProperty("java.io.tmpdir")
                + File.separator + "log-record-sample" + File.separator + "export";
        new File(exportDir).mkdirs();

        long now = System.currentTimeMillis();
        long oneDayAgo = now - 86_400_000L;

        // 导出为 JSON（异步执行，结果通过回调通知）
        logger.exportLogs(
                LogQuery.builder()
                        .fromTime(oneDayAgo)
                        .toTime(now)
                        .orderBy(OrderBy.ASC)
                        .build(),
                ExportFormat.JSON,
                exportDir + File.separator + "logs.json",
                new ExportCallback() {
                    @Override
                    public void onProgress(int exported, int total) {
                        // 导出进度（total=-1 表示总数未知）
                    }

                    @Override
                    public void onSuccess(String filePath, int totalCount) {
                        System.out.println("==== 导出 JSON 成功: " + filePath
                                + " (" + totalCount + " 条)");
                    }

                    @Override
                    public void onFailure(Throwable error, int exportedCount) {
                        System.out.println("==== 导出 JSON 失败: " + error.getMessage()
                                + "，已导出 " + exportedCount + " 条");
                    }
                });

        // 导出为 CSV
        logger.exportLogs(
                LogQuery.builder()
                        .level(LogLevel.ERROR)
                        .fromTime(oneDayAgo)
                        .toTime(now)
                        .orderBy(OrderBy.ASC)
                        .build(),
                ExportFormat.CSV,
                exportDir + File.separator + "error_logs.csv",
                new ExportCallback() {
                    @Override
                    public void onProgress(int exported, int total) {
                    }

                    @Override
                    public void onSuccess(String filePath, int totalCount) {
                        System.out.println("==== 导出 CSV 成功: " + filePath
                                + " (" + totalCount + " 条)");
                    }

                    @Override
                    public void onFailure(Throwable error, int exportedCount) {
                    }
                });

        // 导出为 TXT
        logger.exportLogs(
                LogQuery.builder()
                        .type(StandardLogType.NETWORK.code())
                        .orderBy(OrderBy.ASC)
                        .build(),
                ExportFormat.TXT,
                exportDir + File.separator + "network_logs.txt",
                new ExportCallback() {
                    @Override
                    public void onProgress(int exported, int total) {
                    }

                    @Override
                    public void onSuccess(String filePath, int totalCount) {
                        System.out.println("==== 导出 TXT 成功: " + filePath
                                + " (" + totalCount + " 条)");
                    }

                    @Override
                    public void onFailure(Throwable error, int exportedCount) {
                    }
                });

        // 异常独立导出
        logger.exportExceptions(
                ExceptionQuery.builder()
                        .orderBy(OrderBy.ASC)
                        .build(),
                ExportFormat.JSON,
                exportDir + File.separator + "exceptions.json",
                new ExportCallback() {
                    @Override
                    public void onProgress(int exported, int total) {
                    }

                    @Override
                    public void onSuccess(String filePath, int totalCount) {
                        System.out.println("==== 导出异常 JSON 成功: " + filePath
                                + " (" + totalCount + " 条)");
                    }

                    @Override
                    public void onFailure(Throwable error, int exportedCount) {
                    }
                });

        // 等待导出完成（导出在后台线程执行）
        Thread.sleep(1000);
    }

    // ===== 运行时动态配置 =====

    private static void updateConfigSample(LogManager logger) {
        LogConfigUpdater updater = logger.getConfigUpdater();

        // 1) 修改版本标签（无需 apply，立即 CAS 生效）
        updater.updateVersionTag("desktop-sample-1.0.1");

        // 2) 关闭方法名/行号捕获
        updater.updateCaptureMethodLine(false);

        // 3) 修改清理策略（注意：修改后需调用 refreshCleanPolicies() 同步到定时任务）
        CleanPolicy newPolicy = CleanPolicy.builder()
                .enable(true)
                .keepDays(14)                  // 保留天数延长到 14 天
                .maxDbSizeMB(200)
                .maxRecordCount(100000)
                .cleanIntervalHours(12)
                .build();
        updater.updateCleanPolicy(newPolicy);
        logger.refreshCleanPolicies();   // 同步到 CleanTask 定时任务

        // 4) 修改导出编码
        updater.updateExportEncoding(ExportEncoding.UTF_8);

        System.out.println("==== 动态配置已更新，versionTag="
                + logger.getConfig().getVersionTag()
                + ", captureMethodLine=" + logger.getConfig().isCaptureMethodLine());
    }

    // ===== 清理示例 =====

    private static void cleanSample(LogManager logger) {
        // 1) 立即执行一次清理（使用当前配置的策略，回调通知结果）
        logger.cleanNow(new CleanCallback() {
            @Override
            public void onSuccess(int cleanedCount) {
                System.out.println("==== 手动清理完成，清理 " + cleanedCount + " 条");
            }

            @Override
            public void onFailure(Throwable error) {
                System.out.println("==== 手动清理失败: " + error.getMessage());
            }
        });

        // 2) 按时间清理（清理 1 天前的所有日志和异常）
        long oneDayAgo = System.currentTimeMillis() - 86_400_000L;
        int cleaned = logger.cleanBefore(oneDayAgo);
        System.out.println("==== 按时间清理 " + cleaned + " 条");

        // 3) 按保留数量清理（仅保留最近 1000 条）
        int cleanedByCount = logger.cleanByCount(1000);
        System.out.println("==== 按数量清理 " + cleanedByCount + " 条");
    }

    // ===== 状态查询 =====

    private static void printStatus(LogManager logger) {
        LogStatus status = logger.getStatus();
        System.out.println("==== 库状态: " + status);
        System.out.println("  日志总数: " + logger.getLogCount());
        System.out.println("  异常总数: " + logger.getExceptionCount());
        System.out.println("  数据库大小: " + logger.getDbSizeBytes() + " 字节");
    }
}
