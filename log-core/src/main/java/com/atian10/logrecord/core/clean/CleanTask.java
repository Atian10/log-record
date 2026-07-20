package com.atian10.logrecord.core.clean;

import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.config.CleanPolicy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时清理任务
 * <p>
 * 按清理策略周期性清理日志表和异常表。日志与异常使用独立的清理策略。
 * 清理条件（任一触发即清理，由存储实现内部判定）：
 * <ul>
 *   <li>按天数：清理 keepDays 天之前的记录</li>
 *   <li>按容量：数据库大小超过 maxDbSizeMB 时触发清理</li>
 *   <li>按数量：记录数超过 maxRecordCount 时按保留数量清理</li>
 * </ul>
 * </p>
 * <p>
 * 通过单线程调度器实现，避免并发清理。
 * 持有 volatile 策略引用，支持运行时通过 {@link #updatePolicies} 更新策略而无需重启调度。
 * 可通过 {@link #runOnce(CleanCallback)} 立即执行一次。
 * </p>
 */
public final class CleanTask {

    private final IStorage storage;
    private final IExceptionStorage exceptionStorage;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;

    /** 当前日志清理策略（volatile 保证调度线程可见性） */
    private volatile CleanPolicy logCleanPolicy;
    /** 当前异常清理策略 */
    private volatile CleanPolicy exceptionCleanPolicy;
    private volatile boolean running = false;
    /** 最近一次调度清理的异常信息（null 表示无异常），供外部诊断 */
    private volatile String lastError = null;

    /**
     * 构造清理任务
     * @param storage 日志存储
     * @param exceptionStorage 异常存储
     */
    public CleanTask(IStorage storage, IExceptionStorage exceptionStorage) {
        if (storage == null) {
            throw new NullPointerException("storage == null");
        }
        if (exceptionStorage == null) {
            throw new NullPointerException("exceptionStorage == null");
        }
        this.storage = storage;
        this.exceptionStorage = exceptionStorage;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-record-cleaner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动定时清理
     * @param logCleanPolicy 日志清理策略（enabled=false 时不参与日志清理）
     * @param exceptionCleanPolicy 异常清理策略（enabled=false 时不参与异常清理）
     */
    public synchronized void start(CleanPolicy logCleanPolicy,
                                   CleanPolicy exceptionCleanPolicy) {
        if (running) {
            return;
        }
        this.logCleanPolicy = logCleanPolicy == null
                ? CleanPolicy.builder().build() : logCleanPolicy;
        this.exceptionCleanPolicy = exceptionCleanPolicy == null
                ? CleanPolicy.builder().build() : exceptionCleanPolicy;
        // 二者都未开启则不启动调度
        if (!this.logCleanPolicy.isEnabled() && !this.exceptionCleanPolicy.isEnabled()) {
            return;
        }
        // 取较短的清理周期（最少 1 小时）
        int intervalHours = Math.max(1, Math.min(
                this.logCleanPolicy.getCleanIntervalHours(),
                this.exceptionCleanPolicy.getCleanIntervalHours()));
        long intervalMillis = (long) intervalHours * 60L * 60L * 1000L;
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                this::cleanInternal, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        running = true;
    }

    /**
     * 运行时更新清理策略
     * <p>
     * 若原策略均未启用导致调度未启动，而新策略至少有一项启用，则自动启动调度
     * （修复初始 enabled=false 后 updatePolicies 改为 true 调度不启动的缺陷）。
     * 若调度已启动，仅替换 volatile 策略引用，下一轮调度生效（周期不重建）。
     * </p>
     * @param logCleanPolicy 日志清理策略
     * @param exceptionCleanPolicy 异常清理策略
     */
    public synchronized void updatePolicies(CleanPolicy logCleanPolicy,
                                            CleanPolicy exceptionCleanPolicy) {
        CleanPolicy newLogPolicy = logCleanPolicy == null
                ? CleanPolicy.builder().build() : logCleanPolicy;
        CleanPolicy newExpPolicy = exceptionCleanPolicy == null
                ? CleanPolicy.builder().build() : exceptionCleanPolicy;

        // 检测是否需要从"未启用"切换为"启用"并自动启动调度
        boolean wasEnabled = (this.logCleanPolicy != null && this.logCleanPolicy.isEnabled())
                || (this.exceptionCleanPolicy != null && this.exceptionCleanPolicy.isEnabled());
        boolean nowEnabled = newLogPolicy.isEnabled() || newExpPolicy.isEnabled();

        this.logCleanPolicy = newLogPolicy;
        this.exceptionCleanPolicy = newExpPolicy;

        // 原来未启用导致调度未启动，现在启用则自动启动调度
        if (!wasEnabled && nowEnabled && !running) {
            // 取较短的清理周期（最少 1 小时）
            int intervalHours = Math.max(1, Math.min(
                    newLogPolicy.getCleanIntervalHours(),
                    newExpPolicy.getCleanIntervalHours()));
            long intervalMillis = (long) intervalHours * 60L * 60L * 1000L;
            scheduledFuture = scheduler.scheduleWithFixedDelay(
                    this::cleanInternal, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            running = true;
        }
    }

    /**
     * 停止定时清理（保留线程池，可再次 start）
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        running = false;
    }

    /**
     * 关闭清理任务（释放线程资源，不可再 start）
     */
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    /**
     * 立即执行一次清理（不影响定时调度）
     * @param logCleanPolicy 日志清理策略
     * @param exceptionCleanPolicy 异常清理策略
     * @param callback 回调；可为 null
     * @return 清理的记录总数
     */
    public int runOnce(CleanPolicy logCleanPolicy, CleanPolicy exceptionCleanPolicy,
                       CleanCallback callback) {
        int cleaned = 0;
        try {
            CleanPolicy logPolicy = logCleanPolicy == null
                    ? CleanPolicy.builder().build() : logCleanPolicy;
            CleanPolicy expPolicy = exceptionCleanPolicy == null
                    ? CleanPolicy.builder().build() : exceptionCleanPolicy;
            if (logPolicy.isEnabled()) {
                cleaned += storage.clean(logPolicy);
            }
            if (expPolicy.isEnabled()) {
                cleaned += exceptionStorage.clean(expPolicy);
            }
            if (callback != null) {
                callback.onSuccess(cleaned);
            }
        } catch (Throwable t) {
            if (callback != null) {
                callback.onFailure(t);
            }
        }
        return cleaned;
    }

    /**
     * 执行清理（带策略参数，供外部主动调用）
     * @param logCleanPolicy 日志清理策略
     * @param exceptionCleanPolicy 异常清理策略
     * @return 清理的记录总数
     */
    public int clean(CleanPolicy logCleanPolicy, CleanPolicy exceptionCleanPolicy) {
        return runOnce(logCleanPolicy, exceptionCleanPolicy, null);
    }

    /**
     * 定时调度的内部清理逻辑：读取当前 volatile 策略并执行清理
     */
    private void cleanInternal() {
        try {
            CleanPolicy logPolicy = this.logCleanPolicy;
            CleanPolicy expPolicy = this.exceptionCleanPolicy;
            if (logPolicy != null && logPolicy.isEnabled()) {
                storage.clean(logPolicy);
            }
            if (expPolicy != null && expPolicy.isEnabled()) {
                exceptionStorage.clean(expPolicy);
            }
            // 清理成功，清除上次异常
            lastError = null;
        } catch (Throwable t) {
            // 调度任务异常不应中断后续调度，但记录最近一次异常供外部诊断
            lastError = "cleanInternal failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
        }
    }

    /**
     * 获取最近一次调度清理的异常信息
     * @return 异常描述，null 表示无异常
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 是否正在调度
     */
    public boolean isRunning() {
        return running;
    }
}
