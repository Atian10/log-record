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
 * 按清理策略周期性清理日志表和异常表。清理分两个阶段（B4）：
 * </p>
 * <ol>
 *   <li><b>表级规则</b>：日志与异常各自按 keepDays 过期删除和 maxRecordCount 数量清理，
 *       互不影响；表级不再按容量删除（旧 maxDbSizeMB 仅作为预算迁移来源）</li>
 *   <li><b>全库容量阶段</b>：存在有效容量预算且配置了 {@link CapacityCoordinator} 时，
 *       在表级规则之后按预算执行全库容量维护；未达标/推迟/失败记录到
 *       {@link #getLastError()} 与 {@link #getLastCapacityResult()}，不虚报成功</li>
 * </ol>
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

    /** 全库容量协调器（null 表示无容量清理能力） */
    private final CapacityCoordinator capacityCoordinator;
    /** 当前容量预算供应者（从当前配置解析；null 表示不执行容量阶段） */
    private final java.util.function.Supplier<CapacityBudget> budgetSupplier;

    /** 当前日志清理策略（volatile 保证调度线程可见性） */
    private volatile CleanPolicy logCleanPolicy;
    /** 当前异常清理策略 */
    private volatile CleanPolicy exceptionCleanPolicy;
    private volatile boolean running = false;
    /** 最近一次调度清理的异常信息（null 表示无异常），供外部诊断 */
    private volatile String lastError = null;
    /** 最近一次容量维护结果（null 表示尚未执行），供外部诊断 */
    private volatile CleanResult lastCapacityResult = null;

    /**
     * 构造清理任务（无容量阶段，兼容入口）
     * @param storage 日志存储
     * @param exceptionStorage 异常存储
     */
    public CleanTask(IStorage storage, IExceptionStorage exceptionStorage) {
        this(storage, exceptionStorage, null, null);
    }

    /**
     * 构造清理任务（含全库容量阶段）
     * @param storage 日志存储
     * @param exceptionStorage 异常存储
     * @param capacityCoordinator 容量协调器；null 表示无容量清理能力
     * @param budgetSupplier 容量预算供应者（从当前配置解析）；null 表示不执行容量阶段
     */
    public CleanTask(IStorage storage, IExceptionStorage exceptionStorage,
                     CapacityCoordinator capacityCoordinator,
                     java.util.function.Supplier<CapacityBudget> budgetSupplier) {
        if (storage == null) {
            throw new NullPointerException("storage == null");
        }
        if (exceptionStorage == null) {
            throw new NullPointerException("exceptionStorage == null");
        }
        this.storage = storage;
        this.exceptionStorage = exceptionStorage;
        this.capacityCoordinator = capacityCoordinator;
        this.budgetSupplier = budgetSupplier;
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
     * <p>兼容入口：默认等待清理线程 5 秒退出，忽略结果</p>
     */
    public void shutdown() {
        shutdown(5000L);
    }

    /**
     * 关闭清理任务并等待清理线程退出
     * <p>
     * 停止调度后中断在执行的清理并等待其退出。超时返回 false 时清理线程可能仍在
     * 使用存储，调用方不应立即释放底层数据库资源，应改为轮询 {@link #isTerminated()}。
     * </p>
     * @param timeoutMillis 等待清理线程退出的时限（毫秒），负数按 0 处理
     * @return 清理线程是否在超时前退出
     */
    public boolean shutdown(long timeoutMillis) {
        stop();
        scheduler.shutdownNow();
        long timeout = Math.max(0L, timeoutMillis);
        try {
            return scheduler.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return scheduler.isTerminated();
        }
    }

    /**
     * 清理线程是否已终止
     * <p>调度器尚未关闭时返回 false（运行中不算终止）</p>
     */
    public boolean isTerminated() {
        return scheduler.isTerminated();
    }

    /**
     * 立即执行一次清理（不影响定时调度）
     * @param logCleanPolicy 日志清理策略
     * @param exceptionCleanPolicy 异常清理策略
     * @param callback 回调；可为 null
     * @return 清理的记录总数（仅表级规则；容量阶段删除数见 getLastCapacityResult）
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
            // 全库容量阶段：在表级规则之后独立执行
            runCapacityPhase();
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
            // 全库容量阶段：在表级规则之后独立执行
            runCapacityPhase();
            // 清理成功，清除上次异常
            lastError = null;
        } catch (Throwable t) {
            // 调度任务异常不应中断后续调度，但记录最近一次异常供外部诊断
            lastError = "cleanInternal failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
        }
    }

    /**
     * 全库容量阶段：解析当前预算并委托协调器执行
     * <p>无预算/无协调器时静默跳过（配置缺协调器时记录可观察原因）；
     * 未达标/推迟/失败不视为清理成功，记录到 lastError 供诊断</p>
     */
    private void runCapacityPhase() {
        if (budgetSupplier == null) {
            return;
        }
        final CapacityBudget budget;
        try {
            budget = budgetSupplier.get();
        } catch (RuntimeException e) {
            // 防御：配置已通过 build() 校验，此处不应出现冲突
            lastError = "capacity budget resolve failed: " + e.getMessage();
            return;
        }
        if (budget == null) {
            return;
        }
        if (capacityCoordinator == null) {
            lastError = "capacity budget present but no CapacityCoordinator configured";
            return;
        }
        CleanResult result = capacityCoordinator.enforceCapacity(budget);
        lastCapacityResult = result;
        if (result.getOutcome() != CleanResult.Outcome.MET) {
            lastError = "capacity maintenance " + result.getOutcome()
                    + ": " + result.getStopReason();
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
     * 获取最近一次全库容量维护结果
     * @return 结果；尚未执行过容量阶段时返回 null
     */
    public CleanResult getLastCapacityResult() {
        return lastCapacityResult;
    }

    /**
     * 是否正在调度
     */
    public boolean isRunning() {
        return running;
    }
}
