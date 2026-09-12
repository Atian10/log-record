package com.atian10.logrecord.core;

import com.atian10.logrecord.core.clean.CleanCallback;
import com.atian10.logrecord.core.clean.CleanTask;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.config.LogConfigUpdater;
import com.atian10.logrecord.core.engine.AsyncLoggerEngine;
import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.core.engine.LogStatus;
import com.atian10.logrecord.core.engine.ShutdownResult;
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
import java.util.concurrent.TimeUnit;

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

    /** 关闭流程各阶段的默认等待时限（毫秒） */
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5000L;

    /** 全局单例实例 */
    private static volatile LogManager instance;

    /**
     * 实例生命周期
     * <p>区分于 {@link LogStatus} 的健康状态；仅由本实例的关闭流程推进</p>
     */
    private enum Lifecycle {
        /** 运行中 */
        RUNNING,
        /** 关闭中（停止接收、停止清理、等待排空） */
        CLOSING,
        /** 已关闭 */
        CLOSED
    }

    private final LogConfigUpdater configUpdater;
    private final AsyncLoggerEngine engine;
    private final Exporter exporter;
    private final CleanTask cleanTask;
    /** 缺省格式化器（初始化时确定，未配置 formatter 时为 DefaultFormatter）；当前生效值经 {@link #currentFormatter} 读取 */
    private final IFormatter effectiveFormatter;
    /** 内部告警最近一条快照（含 storage/clean/export 等），供业务方诊断 */
    private volatile String lastWarning = null;
    /** 实例生命周期状态 */
    private volatile Lifecycle lifecycle = Lifecycle.RUNNING;
    /** 最近一次关闭结果（shutdown(long) 内同步保护），重复关闭时直接返回 */
    private volatile ShutdownResult lastShutdownResult = null;
    /** 数据库共同所有者及唯一后台收尾线程；等待在本实例 monitor 外进行。 */
    private final DatabaseOperationGuard operationGuard;
    private final Runnable databaseCloser;
    private volatile Thread shutdownWorker;

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
        this.operationGuard = config.getDatabaseOperationGuard() != null
                ? config.getDatabaseOperationGuard() : new DatabaseOperationGuard();
        this.databaseCloser = config.getDatabaseCloser();
        // 缺省格式化器：初始化时配置的 formatter，未配置时使用 DefaultFormatter；
        // 运行时更新 formatter 只影响导出与控制台输出（每次使用时读取当前配置）
        this.effectiveFormatter = config.getFormatter() != null
                ? config.getFormatter() : new DefaultFormatter();
        this.engine = new AsyncLoggerEngine(
                config.getStorage(),
                config.getExceptionStorage(),
                config.getQueueCapacity(),
                config.getQueueFullPolicy(),
                config.getBatchSize(),
                config.getBatchIntervalMillis(), operationGuard);
        this.exporter = new Exporter(
                config.getStorage(),
                config.getExceptionStorage(),
                effectiveFormatter);
        // 清理任务注入容量协调器与预算供应者（B4：表级规则之后执行全库容量阶段）
        this.cleanTask = new CleanTask(config.getStorage(), config.getExceptionStorage(),
                config.getCapacityCoordinator(),
                () -> com.atian10.logrecord.core.config.CapacityBudgets.resolve(configUpdater.get()), operationGuard);
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

    /**
     * 带超时的立即落盘
     * <p>语义见 {@link AsyncLoggerEngine#flush(long)}：只等待调用时刻已接收记录全部终态</p>
     * @param timeoutMillis 等待时限（毫秒），负数按 0 处理
     * @return 等待结果
     */
    public FlushResult flush(long timeoutMillis) {
        return engine.flush(timeoutMillis);
    }

    @Override
    public void shutdown() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT_MILLIS);
    }

    /**
     * 封闭准入后等待同一个后台收尾者；零或负时限仅返回状态。
     * 超时保留 CLOSING 和单例，已有操作、清理和排空结束且资源关闭后才允许重建。
     * 调用线程持有操作许可时立即返回，避免等待自身完成。
     */
    public ShutdownResult shutdown(long timeoutMillis) {
        // 整个调用共享一个 elapsed 预算，线程之间不在管理器 monitor 下相互等待。
        long started = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        startShutdown();
        Thread closer = shutdownWorker;
        if (!operationGuard.isCurrentThreadActive() && closer != Thread.currentThread()) {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining > 0L) {
                try { TimeUnit.NANOSECONDS.timedJoin(closer, remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        if (lifecycle == Lifecycle.CLOSED) return lastShutdownResult;
        return ShutdownResult.timeout(engine.flush(0L).getPending(), cleanTask.isTerminated());
    }

    /** 只串行化一次状态转换和线程发布，所有耗时等待在 monitor 外执行。 */
    private synchronized void startShutdown() {
        if (lifecycle != Lifecycle.RUNNING) return;
        lifecycle = Lifecycle.CLOSING;
        engine.stopAccepting();
        operationGuard.beginClosing();
        cleanTask.shutdown(0L);
        shutdownWorker = new Thread(this::finishShutdown, "log-record-owner-closer");
        shutdownWorker.setDaemon(true);
        shutdownWorker.start();
    }

    /** 唯一资源收尾者：等写入、清理及整个快照退出，再释放本所有者数据库。 */
    private void finishShutdown() {
        try {
            while (!engine.isTerminated()) engine.awaitShutdown(1000L);
            while (!cleanTask.isTerminated()) cleanTask.shutdown(1000L);
            while (!operationGuard.awaitIdle(1000L)) {
                if (Thread.currentThread().isInterrupted()) return;
            }
            if (databaseCloser != null) databaseCloser.run();
            lastShutdownResult = ShutdownResult.completed(0L, true);
            synchronized (LogManager.class) {
                if (instance == this) instance = null;
                // 完成态最后发布，看到完成的调用方可以立即重新初始化。
                lifecycle = Lifecycle.CLOSED;
            }
        } catch (Throwable failure) {
            // 资源关闭失败保持 CLOSING，不伪造 fullyTerminated 或允许新实例重用资源。
            lastWarning = "database shutdown failed: " + failure.getMessage();
        }
    }

    /** 包括平台资源关闭在内的收尾是否完成；超时后会由后台推进到 true。 */
    public boolean isTerminated() { return lifecycle == Lifecycle.CLOSED; }
    /** 平台重复初始化可复用 RUNNING 实例，但不能复用正在关闭的实例。 */
    public boolean isClosing() { return lifecycle != Lifecycle.RUNNING; }

    /** 对查询、导出、清理和配置等新业务入口拒绝关闭后的调用。 */
    private void ensureRunning() {
        if (lifecycle != Lifecycle.RUNNING) throw new IllegalStateException("LogManager is closing");
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
        // 控制台输出与导出一致使用当前配置的格式化器（未配置时用缺省）
        return currentFormatter(configUpdater.get()).format(record, ExportFormat.TXT);
    }

    // ===== 查询方法 =====

    /**
     * 查询日志
     */
    public List<LogRecord> queryLogs(LogQuery query) {
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
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
    }

    /**
     * 查询异常
     */
    public List<ExceptionRecord> queryExceptions(ExceptionQuery query) {
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
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
    }

    /**
     * 聚合统计日志
     */
    public LogStatistics statistics(LogQuery query) {
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
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
    }

    // ===== 导出方法 =====

    /**
     * 导出日志到文件
     * <p>导出开始时从当前配置捕获 formatter 与编码，本次导出全程使用同一快照；
     * 中途更新配置只影响下一次导出（ISSUE-08）</p>
     * @return 导出条数
     */
    public int exportLogs(LogQuery query, ExportFormat format, String filePath,
                          ExportCallback callback) {
        ensureRunning();
        LogConfig config = configUpdater.get();
        return exporter.exportLogs(query, format, config.getExportEncoding(),
                currentFormatter(config), filePath, callback);
    }

    /**
     * 导出异常到文件
     * <p>导出开始时从当前配置捕获 formatter 与编码，本次导出全程使用同一快照</p>
     * @return 导出条数
     */
    public int exportExceptions(ExceptionQuery query, ExportFormat format, String filePath,
                                ExportCallback callback) {
        ensureRunning();
        LogConfig config = configUpdater.get();
        return exporter.exportExceptions(query, format, config.getExportEncoding(),
                currentFormatter(config), filePath, callback);
    }

    /**
     * 读取当前生效的格式化器：配置了 formatter 用配置值，否则用缺省（DefaultFormatter）
     */
    private IFormatter currentFormatter(LogConfig config) {
        return config.getFormatter() != null ? config.getFormatter() : effectiveFormatter;
    }

    // ===== 清理方法 =====

    /**
     * 立即执行一次清理（使用当前配置的策略）
     * @param callback 回调；可为 null
     * @return 清理条数
     */
    public int cleanNow(CleanCallback callback) {
        ensureRunning();
        LogConfig config = configUpdater.get();
        return cleanTask.runOnce(config.getCleanPolicy(),
                config.getExceptionCleanPolicy(), callback);
    }

    /** 最近一轮全库容量结果；null 表示该轮未执行容量阶段，MET 才代表达标。 */
    public com.atian10.logrecord.core.clean.CleanResult getLastCapacityResult() {
        return cleanTask.getLastCapacityResult();
    }

    /**
     * 清理指定时间之前的日志和异常
     * @param timestamp 时间戳（毫秒）
     * @return 清理条数
     */
    public int cleanBefore(long timestamp) {
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
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
    }

    /**
     * 按保留数量清理日志和异常
     * @param keepCount 保留数量
     * @return 清理条数
     */
    public int cleanByCount(int keepCount) {
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
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
        ensureRunning();
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
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
        try {
            return configUpdater.get().getStorage().getRecordCount();
        } catch (Throwable t) {
            recordInternalWarning("storage.getRecordCount failed", t);
            return -1;
        }
        }
    }

    /**
     * 获取异常总记录数
     */
    public long getExceptionCount() {
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
        try {
            return configUpdater.get().getExceptionStorage().getRecordCount();
        } catch (Throwable t) {
            recordInternalWarning("exceptionStorage.getRecordCount failed", t);
            return -1;
        }
        }
    }

    /**
     * 获取数据库大小（字节）
     */
    public long getDbSizeBytes() {
        ensureRunning();
        // 门面复合操作跨两张表时仍使用一个存活期许可。
        try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
        try {
            return configUpdater.get().getStorage().getDbSizeBytes();
        } catch (Throwable t) {
            recordInternalWarning("storage.getDbSizeBytes failed", t);
            return -1;
        }
        }
    }

    /**
     * 获取当前生效的格式化器
     * <p>运行时通过配置更新器修改 formatter 后，本方法与后续导出均使用新值；
     * 未配置 formatter 时返回初始化时确定的缺省（DefaultFormatter）</p>
     */
    public IFormatter getFormatter() {
        return currentFormatter(configUpdater.get());
    }

    /**
     * 运行时修改清理策略后，同步更新 CleanTask 的策略
     * <p>建议在通过 getConfigUpdater().updateCleanPolicy/updateExceptionCleanPolicy 后调用</p>
     */
    public void refreshCleanPolicies() {
        ensureRunning();
        LogConfig config = configUpdater.get();
        cleanTask.updatePolicies(config.getCleanPolicy(),
                config.getExceptionCleanPolicy());
    }
}
