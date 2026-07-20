package com.atian10.logrecord.core;

import com.atian10.logrecord.core.clean.CleanCallback;
import com.atian10.logrecord.core.clean.CleanTask;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.config.LogConfigUpdater;
import com.atian10.logrecord.core.engine.AsyncLoggerEngine;
import com.atian10.logrecord.core.engine.LogStatus;
import com.atian10.logrecord.core.export.ExportCallback;
import com.atian10.logrecord.core.export.ExportEncoding;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.export.Exporter;
import com.atian10.logrecord.core.formatter.DefaultFormatter;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.model.StandardLogType;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.util.StackTraceUtil;
import com.atian10.logrecord.core.util.TimeUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 日志库全局门面
 * <p>
 * 单例入口，业务方通过本类初始化日志库、写入日志、查询、导出、清理、动态修改配置。
 * 内部组合 {@link AsyncLoggerEngine}、{@link Exporter}、{@link CleanTask}、{@link LogConfigUpdater}。
 * </p>
 * <p>
 * 使用流程：
 * <ol>
 *   <li>{@link #init(LogConfig)} 初始化（平台适配层调用）</li>
 *   <li>{@link #getLogger()} 或静态便捷方法写入日志</li>
 *   <li>查询/导出/清理</li>
 *   <li>{@link #shutdown()} 关闭</li>
 * </ol>
 * </p>
 */
public final class LogManager implements ILogger {

    // ===== 调用栈跳转层数 =====
    // 调用链：业务代码 -> LogManager.x -> buildLogRecord -> StackTraceUtil.captureCaller
    // captureCaller 自身占 1 帧，buildLogRecord 占 1 帧，LogManager.x 占 1 帧
    private static final int SKIP_FRAMES_FROM_BUILD = 3;

    private static volatile LogManager instance;

    private final LogConfigUpdater configUpdater;
    private final AsyncLoggerEngine engine;
    private final Exporter exporter;
    private final CleanTask cleanTask;
    private final IFormatter effectiveFormatter;
    /** 内部告警最近一条快照（含 storage/clean/export 等），供业务方诊断 */
    private volatile String lastWarning = null;

    /**
     * 初始化日志库
     * @param config 配置，不可为 null
     * @return 单例实例
     * @throws IllegalStateException 重复初始化时抛出
     */
    public static synchronized LogManager init(LogConfig config) {
        if (instance != null) {
            throw new IllegalStateException("LogManager already initialized");
        }
        instance = new LogManager(config);
        return instance;
    }

    /**
     * 获取单例
     * @return 已初始化的实例
     * @throws IllegalStateException 未初始化时抛出
     */
    public static LogManager get() {
        LogManager m = instance;
        if (m == null) {
            throw new IllegalStateException("LogManager not initialized, call init() first");
        }
        return m;
    }

    /**
     * 获取 ILogger 接口（便于业务方持有）
     */
    public static ILogger getLogger() {
        return get();
    }

    /**
     * 是否已初始化
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    private LogManager(LogConfig config) {
        if (config == null) {
            throw new NullPointerException("config == null");
        }
        this.configUpdater = new LogConfigUpdater(config);
        this.effectiveFormatter = config.getFormatter() != null
                ? config.getFormatter() : new DefaultFormatter();
        this.engine = new AsyncLoggerEngine(
                config.getStorage(),
                config.getExceptionStorage(),
                config.getQueueCapacity(),
                config.getQueueFullPolicy(),
                config.getBatchSize(),
                config.getBatchIntervalMillis());
        this.exporter = new Exporter(
                config.getStorage(),
                config.getExceptionStorage(),
                effectiveFormatter);
        this.cleanTask = new CleanTask(config.getStorage(), config.getExceptionStorage());
        // 启动定时清理
        cleanTask.start(config.getCleanPolicy(), config.getExceptionCleanPolicy());
    }

    // ===== ILogger 写入方法 =====
    // 便捷方法 extraSkip=0（与 log() 栈帧深度相同，都直接调用 submitLog，无额外栈帧）
    // 保证 captureMethodLine 时定位到业务调用者本身

    @Override
    public void d(String tag, String message) {
        submitLog(LogLevel.DEBUG, StandardLogType.SYSTEM.code(), tag, message, null, null, 0);
    }

    @Override
    public void i(String tag, String message) {
        submitLog(LogLevel.INFO, StandardLogType.SYSTEM.code(), tag, message, null, null, 0);
    }

    @Override
    public void w(String tag, String message) {
        submitLog(LogLevel.WARN, StandardLogType.SYSTEM.code(), tag, message, null, null, 0);
    }

    @Override
    public void e(String tag, String message) {
        submitLog(LogLevel.ERROR, StandardLogType.SYSTEM.code(), tag, message, null, null, 0);
    }

    @Override
    public void f(String tag, String message) {
        submitLog(LogLevel.FATAL, StandardLogType.SYSTEM.code(), tag, message, null, null, 0);
    }

    @Override
    public void e(String tag, String message, Throwable throwable) {
        submitLog(LogLevel.ERROR, StandardLogType.SYSTEM.code(), tag, message, null, throwable, 0);
    }

    @Override
    public void f(String tag, String message, Throwable throwable) {
        submitLog(LogLevel.FATAL, StandardLogType.SYSTEM.code(), tag, message, null, throwable, 0);
    }

    @Override
    public void log(LogLevel level, String type, String tag, String message) {
        submitLog(level, type, tag, message, null, null, 0);
    }

    @Override
    public void log(LogLevel level, String type, String tag, String message,
                    Map<String, String> userFields) {
        submitLog(level, type, tag, message, userFields, null, 0);
    }

    @Override
    public void log(LogLevel level, String type, String tag, String message,
                    Throwable throwable) {
        submitLog(level, type, tag, message, null, throwable, 0);
    }

    @Override
    public void log(LogLevel level, String type, String tag, String message,
                    Map<String, String> userFields, Throwable throwable) {
        submitLog(level, type, tag, message, userFields, throwable, 0);
    }

    @Override
    public void recordException(Throwable throwable, String tag) {
        if (throwable == null) {
            return;
        }
        long timestamp = TimeUtil.now();
        String exceptionClass = StackTraceUtil.getClassName(throwable);
        String exceptionMessage = StackTraceUtil.getMessage(throwable);
        String stackTrace = StackTraceUtil.stackTraceToString(throwable);
        ExceptionRecord record = new ExceptionRecord(
                timestamp, exceptionClass, exceptionMessage, stackTrace, tag);
        engine.submit(record);
        if (consoleEnabled()) {
            printConsole("[EXCEPTION] " + tag + " " + exceptionClass
                    + ": " + exceptionMessage + "\n" + stackTrace);
        }
    }

    @Override
    public void flush() {
        engine.flush();
    }

    @Override
    public void shutdown() {
        try {
            engine.flush();
        } finally {
            cleanTask.shutdown();
            engine.shutdown();
        }
        // 重置静态单例，允许业务方重新 init（用于进程内重启日志库场景）
        synchronized (LogManager.class) {
            instance = null;
        }
    }

    // ===== 内部写入逻辑 =====

    /**
     * 提交日志到引擎
     * @param extraSkip 调用者栈帧额外跳过数（便捷方法 d/i/w/e/f 与 log 重载均传 0，
     *                  因为二者调用栈帧深度相同：都直接调用本方法）
     */
    private void submitLog(LogLevel level, String type, String tag, String message,
                           Map<String, String> userFields, Throwable throwable,
                           int extraSkip) {
        LogConfig config = configUpdater.get();
        LogRecord record = buildLogRecord(level, type, tag, message, userFields,
                throwable != null, config, extraSkip);
        // 应用过滤器
        ILogFilter filter = config.getLogFilter();
        if (filter != null && !filter.accept(record)) {
            return;
        }
        engine.submit(record);
        // 异常独立写入
        if (throwable != null) {
            recordException(throwable, tag);
        }
        // 控制台输出
        if (config.isConsoleEnabled()) {
            printConsole(formatForConsole(record));
        }
    }

    /**
     * 构建 LogRecord，捕获系统字段
     * @param extraSkip 调用者栈帧额外跳过数
     */
    private LogRecord buildLogRecord(LogLevel level, String type, String tag,
                                     String message, Map<String, String> userFields,
                                     boolean hasException, LogConfig config, int extraSkip) {
        long timestamp = TimeUtil.now();
        Thread current = Thread.currentThread();
        String threadName = current.getName();
        long threadId = current.getId();

        String methodName = null;
        int lineNumber = 0;
        if (config.isCaptureMethodLine()) {
            StackTraceUtil.Caller caller = StackTraceUtil.captureCaller(
                    SKIP_FRAMES_FROM_BUILD + extraSkip);
            methodName = caller.methodName;
            lineNumber = caller.lineNumber;
        }

        String versionTag = config.getVersionTag();

        return new LogRecord(
                timestamp,
                level,
                type,
                tag,
                threadName,
                threadId,
                methodName,
                lineNumber,
                versionTag,
                hasException,
                message,
                userFields);
    }

    private boolean consoleEnabled() {
        return configUpdater.get().isConsoleEnabled();
    }

    private void printConsole(String text) {
        // 默认输出到 stdout，平台适配层可重写输出通道
        System.out.println(text);
    }

    private String formatForConsole(LogRecord record) {
        // 控制台用 DefaultFormatter 的 TXT 格式
        return effectiveFormatter.format(record, ExportFormat.TXT);
    }

    // ===== 查询方法 =====

    /**
     * 查询日志
     */
    public List<LogRecord> queryLogs(LogQuery query) {
        if (query == null) {
            return Collections.emptyList();
        }
        try {
            List<LogRecord> result = configUpdater.get().getStorage().query(query);
            return result == null ? Collections.<LogRecord>emptyList() : result;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * 查询异常
     */
    public List<ExceptionRecord> queryExceptions(ExceptionQuery query) {
        if (query == null) {
            return Collections.emptyList();
        }
        try {
            List<ExceptionRecord> result = configUpdater.get().getExceptionStorage().query(query);
            return result == null ? Collections.<ExceptionRecord>emptyList() : result;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * 聚合统计日志
     */
    public LogStatistics statistics(LogQuery query) {
        if (query == null) {
            return new LogStatistics(0, null, null, null);
        }
        try {
            LogStatistics result = configUpdater.get().getStorage().statistics(query);
            return result == null ? new LogStatistics(0, null, null, null) : result;
        } catch (Throwable t) {
            return new LogStatistics(0, null, null, null);
        }
    }

    // ===== 导出方法 =====

    /**
     * 导出日志到文件
     * @return 导出条数
     */
    public int exportLogs(LogQuery query, ExportFormat format, String filePath,
                          ExportCallback callback) {
        LogConfig config = configUpdater.get();
        return exporter.exportLogs(query, format, config.getExportEncoding(),
                filePath, callback);
    }

    /**
     * 导出异常到文件
     * @return 导出条数
     */
    public int exportExceptions(ExceptionQuery query, ExportFormat format, String filePath,
                                ExportCallback callback) {
        LogConfig config = configUpdater.get();
        return exporter.exportExceptions(query, format, config.getExportEncoding(),
                filePath, callback);
    }

    // ===== 清理方法 =====

    /**
     * 立即执行一次清理（使用当前配置的策略）
     * @param callback 回调；可为 null
     * @return 清理条数
     */
    public int cleanNow(CleanCallback callback) {
        LogConfig config = configUpdater.get();
        return cleanTask.runOnce(config.getCleanPolicy(),
                config.getExceptionCleanPolicy(), callback);
    }

    /**
     * 清理指定时间之前的日志和异常
     * @param timestamp 时间戳（毫秒）
     * @return 清理条数
     */
    public int cleanBefore(long timestamp) {
        LogConfig config = configUpdater.get();
        int cleaned = 0;
        try {
            cleaned += config.getStorage().cleanBefore(timestamp);
        } catch (Throwable t) {
            recordInternalWarning("storage.cleanBefore failed", t);
        }
        try {
            cleaned += config.getExceptionStorage().cleanBefore(timestamp);
        } catch (Throwable t) {
            recordInternalWarning("exceptionStorage.cleanBefore failed", t);
        }
        return cleaned;
    }

    /**
     * 按保留数量清理日志和异常
     * @param keepCount 保留数量
     * @return 清理条数
     */
    public int cleanByCount(int keepCount) {
        LogConfig config = configUpdater.get();
        int cleaned = 0;
        try {
            cleaned += config.getStorage().cleanByCount(keepCount);
        } catch (Throwable t) {
            recordInternalWarning("storage.cleanByCount failed", t);
        }
        try {
            cleaned += config.getExceptionStorage().cleanByCount(keepCount);
        } catch (Throwable t) {
            recordInternalWarning("exceptionStorage.cleanByCount failed", t);
        }
        return cleaned;
    }

    // ===== 配置与状态方法 =====

    /**
     * 获取当前配置
     */
    public LogConfig getConfig() {
        return configUpdater.get();
    }

    /**
     * 获取配置更新器（用于运行时动态修改配置）
     */
    public LogConfigUpdater getConfigUpdater() {
        return configUpdater;
    }

    /**
     * 获取引擎状态
     */
    public LogStatus getStatus() {
        return engine.getStatus();
    }

    /**
     * 获取内部告警最近一条快照（含 storage/clean/export 等）
     * <p>业务方可定期检查此方法判断日志库是否健康运行</p>
     * @return 告警描述，null 表示无告警
     */
    public String getLastWarning() {
        String managerWarning = lastWarning;
        if (managerWarning != null) {
            return managerWarning;
        }
        // 合并 CleanTask 的最近调度异常
        return cleanTask.getLastError();
    }

    /**
     * 记录内部告警（包级可见，供本模块内部使用）
     */
    void recordInternalWarning(String message, Throwable t) {
        lastWarning = message + ": " + t.getClass().getSimpleName()
                + ": " + t.getMessage();
    }

    /**
     * 获取日志总记录数
     */
    public long getLogCount() {
        try {
            return configUpdater.get().getStorage().getRecordCount();
        } catch (Throwable t) {
            recordInternalWarning("storage.getRecordCount failed", t);
            return -1;
        }
    }

    /**
     * 获取异常总记录数
     */
    public long getExceptionCount() {
        try {
            return configUpdater.get().getExceptionStorage().getRecordCount();
        } catch (Throwable t) {
            recordInternalWarning("exceptionStorage.getRecordCount failed", t);
            return -1;
        }
    }

    /**
     * 获取数据库大小（字节）
     */
    public long getDbSizeBytes() {
        try {
            return configUpdater.get().getStorage().getDbSizeBytes();
        } catch (Throwable t) {
            recordInternalWarning("storage.getDbSizeBytes failed", t);
            return -1;
        }
    }

    /**
     * 获取格式化器
     */
    public IFormatter getFormatter() {
        return effectiveFormatter;
    }

    /**
     * 运行时修改清理策略后，同步更新 CleanTask 的策略
     * <p>建议在通过 getConfigUpdater().updateCleanPolicy/updateExceptionCleanPolicy 后调用</p>
     */
    public void refreshCleanPolicies() {
        LogConfig config = configUpdater.get();
        cleanTask.updatePolicies(config.getCleanPolicy(),
                config.getExceptionCleanPolicy());
    }
}
