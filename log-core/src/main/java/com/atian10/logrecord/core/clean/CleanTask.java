package com.atian10.logrecord.core.clean;

import com.atian10.logrecord.core.DatabaseOperationGuard;

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
 * 定时调度使用单线程；手动与调度入口共同通过 roundLock 避免并发清理。
 * 持有 volatile 策略引用，支持运行时通过 {@link #updatePolicies} 更新策略而无需重启调度。
 * 可通过 {@link #runOnce(CleanPolicy, CleanPolicy, CleanCallback)} 立即执行一次。
 * </p>
 */
public final class CleanTask {
    /** API 21 可用的预算供应接口，避免引用 API 24 的 java.util.function。 */
    public interface BudgetSupplier {
        /** 返回当前预算；null 表示未启用容量维护。 */
        CapacityBudget get();
    }

    /** 手动与定时清理共用数据库操作保护，回调在归还许可之后执行。 */
    private final DatabaseOperationGuard operationGuard;
    private final IStorage storage;
    private final IExceptionStorage exceptionStorage;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;

    /** 全库容量协调器（null 表示无容量清理能力） */
    private final CapacityCoordinator capacityCoordinator;
    /** 当前容量预算供应者（从当前配置解析；null 表示不执行容量阶段） */
    private final BudgetSupplier budgetSupplier;

    /** 当前日志清理策略（volatile 保证调度线程可见性） */
    private volatile CleanPolicy logCleanPolicy;
    /** 当前异常清理策略 */
    private volatile CleanPolicy exceptionCleanPolicy;
    private volatile boolean running = false;
    /** 手动与定时轮次串行化；用户回调和关闭等待不持有此锁。 */
    private final Object roundLock = new Object();
    /** 原子发布同一轮的表级计数、容量结果与错误，避免旧结果和新错误混配。 */
    private volatile RoundResult lastRound = new RoundResult(0, null, null);

    /** 不可变轮次快照；容量未执行时 result 为 null。 */
    private static final class RoundResult {
        final int cleaned;
        final CleanResult capacity;
        final Throwable failure;
        /** 汇总整轮结果，由轮次结束后一次发布。 */
        RoundResult(int cleaned, CleanResult capacity, Throwable failure) {
            this.cleaned = cleaned;
            this.capacity = capacity;
            this.failure = failure;
        }
    }

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
                     BudgetSupplier budgetSupplier) {
        this(storage, exceptionStorage, capacityCoordinator, budgetSupplier,
                new DatabaseOperationGuard());
    }
    /** 注入平台共同的操作保护，确保关闭等待整个清理轮次。 */
    public CleanTask(IStorage storage, IExceptionStorage exceptionStorage,
                     CapacityCoordinator capacityCoordinator, BudgetSupplier budgetSupplier,
                     DatabaseOperationGuard operationGuard) {
        if (storage == null) {
            throw new NullPointerException("storage == null");
        }
        if (exceptionStorage == null) {
            throw new NullPointerException("exceptionStorage == null");
        }
        if (operationGuard == null) throw new NullPointerException("operationGuard == null");
        this.operationGuard = operationGuard;
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
     * 停止调度后等待已准入清理自然退出，不中断正在提交的事务。超时返回 false 时清理线程可能仍在
     * 使用存储，调用方不应立即释放底层数据库资源，应改为轮询 {@link #isTerminated()}。
     * </p>
     * @param timeoutMillis 等待清理线程退出的时限（毫秒），负数按 0 处理
     * @return 清理线程是否在超时前退出
     */
    public boolean shutdown(long timeoutMillis) {
        stop();
        scheduler.shutdown();
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
        RoundResult round = executeRound(logCleanPolicy, exceptionCleanPolicy);
        // 每轮只调用一个终态；回调在存活许可归还之后执行，允许回调发起关闭。
        if (callback != null) {
            try {
                if (round.failure == null) callback.onSuccess(round.cleaned);
                else callback.onFailure(round.failure);
            } catch (Throwable callbackError) {
                synchronized (roundLock) {
                    // 新轮次已经发布时，不让旧回调覆盖新轮次的诊断结果。
                    if (lastRound == round) {
                        IllegalStateException error = new IllegalStateException("clean callback failed", callbackError);
                        if (round.failure != null) error.addSuppressed(round.failure);
                        lastRound = new RoundResult(round.cleaned, round.capacity, error);
                    }
                }
            }
        }
        return round.cleaned;
    }

    /** 兼容表级删除计数返回值；容量计数和结局从最近轮次结果取得。 */
    public int clean(CleanPolicy logCleanPolicy, CleanPolicy exceptionCleanPolicy) {
        return runOnce(logCleanPolicy, exceptionCleanPolicy, null);
    }

    /** 调度和手动入口共用完整轮次，未达标错误不会被调度成功分支清除。 */
    private void cleanInternal() {
        executeRound(logCleanPolicy, exceptionCleanPolicy);
    }

    /** 在串行区内完成两表规则及容量阶段，并原子发布结果。 */
    private RoundResult executeRound(CleanPolicy logPolicy, CleanPolicy expPolicy) {
        synchronized (roundLock) {
            int cleaned = 0;
            CleanResult capacity = null;
            Throwable failure = null;
            try (DatabaseOperationGuard.Scope operation = operationGuard.enter()) {
                if (logPolicy != null && logPolicy.isEnabled()) cleaned += storage.clean(logPolicy);
                if (expPolicy != null && expPolicy.isEnabled()) cleaned += exceptionStorage.clean(expPolicy);
                capacity = runCapacityPhase();
                if (capacity != null && capacity.getOutcome() != CleanResult.Outcome.MET)
                    failure = new IllegalStateException("capacity maintenance " + capacity.getOutcome()
                            + ": " + capacity.getStopReason());
            } catch (Throwable error) {
                failure = error;
            }
            RoundResult round = new RoundResult(cleaned, capacity, failure);
            lastRound = round;
            return round;
        }
    }

    /** 预算解析、协调器缺失及空结果均作为失败向上传递。 */
    private CleanResult runCapacityPhase() {
        if (budgetSupplier == null) return null;
        CapacityBudget budget = budgetSupplier.get();
        if (budget == null) return null;
        if (capacityCoordinator == null)
            throw new IllegalStateException("capacity budget present but no CapacityCoordinator configured");
        CleanResult result = capacityCoordinator.enforceCapacity(budget);
        if (result == null) throw new IllegalStateException("CapacityCoordinator returned null");
        return result;
    }

    /** 最近一轮手动或调度清理的错误；null 表示该轮成功。 */
    public String getLastError() {
        Throwable failure = lastRound.failure;
        return failure == null ? null : failure.toString();
    }

    /** 最近一轮容量结果；该轮未执行容量阶段时为 null。 */
    public CleanResult getLastCapacityResult() {
        return lastRound.capacity;
    }

    /**
     * 是否正在调度
     */
    public boolean isRunning() {
        return running;
    }
}
