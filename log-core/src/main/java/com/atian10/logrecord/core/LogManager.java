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
    private ShutdownResult lastShutdownResult = null;

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
                config.getBatchIntervalMillis());
        this.exporter = new Exporter(
                config.getStorage(),
                config.getExceptionStorage(),
                effectiveFormatter);
        // 清理任务注入容量协调器与预算供应者（B4：表级规则之后执行全库容量阶段）
        this.cleanTask = new CleanTask(config.getStorage(), config.getExceptionStorage(),
                config.getCapacityCoordinator(),
                () -> com.atian10.logrecord.core.config.CapacityBudgets.resolve(configUpdater.get()));
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
    public synchronized void shutdown() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT_MILLIS);
    }

    /**
     * 带超时关闭日志库
     * <p>
     * 顺序：停止接收新记录 → 停止清理调度并等待清理线程退出 → 等待引擎工作线程
     * 完成排空并退出。队列排空由唯一工作线程负责，调用线程不执行兜底写入。
     * 关闭幂等：已关闭时直接返回上次结果；并发调用会串行化后返回同一结果。
     * </p>
     * <p>
     * 超时返回 {@link ShutdownResult.Outcome#TIMEOUT} 时，工作线程仍在后台排空；
     * 本实例的静态引用仍会被清除以允许重新初始化（新实例不接管本实例资源），
     * 底层数据库资源的释放应由资源所有者等待 {@link #isTerminated()} 为 true 后进行。
     * </p>
     * @param timeoutMillis 各阶段共享的等待时限（毫秒），负数按 0 处理
     * @return 关闭结果
     */
    public synchronized ShutdownResult shutdown(long timeoutMillis) {
        if (lifecycle == Lifecycle.CLOSED) {
            // 幂等：重复关闭返回上次结果，不再触碰任何资源或静态引用
            return lastShutdownResult != null ? lastShutdownResult
                    : ShutdownResult.completed(0L, true);
        }
        lifecycle = Lifecycle.CLOSING;
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        // 1. 停止接收新记录并唤醒引擎工作线程
        engine.stopAccepting();
        // 2. 停止清理调度并等待清理线程退出（先于排空等待，避免清理与排空竞争存储）
        boolean cleanerTerminated = cleanTask.shutdown(remainingMillis(deadlineNanos));
        // 3. 等待工作线程完成排空并退出
        ShutdownResult engineResult = engine.awaitShutdown(remainingMillis(deadlineNanos));
        lifecycle = Lifecycle.CLOSED;
        lastShutdownResult = engineResult.getOutcome() == ShutdownResult.Outcome.COMPLETED
                ? ShutdownResult.completed(engineResult.getPendingCount(), cleanerTerminated)
                : ShutdownResult.timeout(engineResult.getPendingCount(), cleanerTerminated);
        // 仅当静态引用仍指向本实例时清除，避免旧实例关闭清掉新实例（ISSUE-09）；
        // 清除后允许业务方重新 init
        synchronized (LogManager.class) {
            if (instance == this) {
                instance = null;
            }
        }
        return lastShutdownResult;
    }

    /**
     * 计算距截止时刻的剩余毫秒数（不足 1 毫秒按 0 处理）
     */
    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    /**
     * 关闭流程是否已完全结束
     * <p>生命周期已到 CLOSED 且引擎工作线程与清理线程均已退出；
     * 为 true 时释放底层数据库资源才不会中断在途操作</p>
     */
    public boolean isTerminated() {
        return lifecycle == Lifecycle.CLOSED
                && engine.isTerminated()
                && cleanTask.isTerminated();
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
     * <p>导出开始时从当前配置捕获 formatter 与编码，本次导出全程使用同一快照；
     * 中途更新配置只影响下一次导出（ISSUE-08）</p>
     * @return 导出条数
     */
    public int exportLogs(LogQuery query, ExportFormat format, String filePath,
                          ExportCallback callback) {
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
        LogConfig config = configUpdater.get();
        cleanTask.updatePolicies(config.getCleanPolicy(),
                config.getExceptionCleanPolicy());
    }
}
