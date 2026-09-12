package com.atian10.logrecord.core.engine;

import com.atian10.logrecord.core.DatabaseOperationGuard;

import com.atian10.logrecord.core.BatchWriteException;
import com.atian10.logrecord.core.ILoggerEngine;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步日志引擎
 * <p>
 * 单工作线程 + {@link LinkedBlockingQueue} 实现异步写入，不阻塞业务线程。
 * 取到首条后非阻塞收集当前队列至 {@code batchSize}；空闲等待按配置间隔及关闭响应上限取小值。
 * 队列满时按 {@link QueueFullPolicy} 处理。
 * </p>
 * <p>
 * <b>flush 等待边界</b>：每条成功接收的记录分配单调递增的接收序号（seq）。
 * {@link #flush(long)} 只等待调用时刻目标序号及之前的未完成记录，之后新增的记录
 * 不延长本次等待。等待条件是"目标范围内全部记录到达终态（保存/失败/丢弃/未知）"，
 * 而不是"队列为空"，因此最后一批尚未提交时不会提前返回，完全空闲时立即完成。
 * 结果与失败通过 {@link FlushResult} 与 {@link #getStatus()} 暴露。
 * </p>
 * <p>
 * <b>写入失败传递</b>：存储 {@code writeBatch} 抛出的异常（含
 * {@link BatchWriteException} 携带的逐条或汇总结果）按证据区分保存、失败与未知，并记录告警，
 * 引擎不在业务线程抛出。批量降级重试由存储层负责，引擎只保留一层计数。
 * </p>
 * <p>
 * <b>关闭与排空</b>：{@link #stopAccepting()} 停止接收，工作线程通过短轮询发现关闭；
 * 队列剩余条目由唯一工作线程在退出前排空（同一工作循环），
 * 调用线程不再执行兜底写入。{@link #awaitShutdown(long)} 等待排空完成并返回
 * {@link ShutdownResult}；超时时后台继续收尾，调用方不应立即释放底层资源。
 * </p>
 * <p>
 * 线程安全：submit 可在任意线程调用；flush/shutdown 同步等待。
 * 锁顺序固定为 stateLock → flushLock，flushLock 内不会获取 stateLock。
 * 引擎关闭后 submit 静默丢弃并计入 warningCount。
 * </p>
 */
public final class AsyncLoggerEngine implements ILoggerEngine {

    /**
     * 单条记录的终态
     */
    private enum FinalState {
        /** 已确认保存 */
        SAVED,
        /** 写入失败 */
        FAILED,
        /** 被队列策略或中断丢弃 */
        DROPPED,
        /** 提交结果或逐条归属未知 */
        UNKNOWN
    }

    /**
     * flush 等待者（flushLock 保护）
     * <p>记录各自的目标序号与等待期间目标范围内新到达终态的计数</p>
     */
    private static final class FlushWaiter {
        /** 本次等待的目标序号上界 */
        long target;
        /** 注册时目标范围内已终态数量 */
        long alreadyFinalized;
        /** 等待期间目标范围内新保存数 */
        long saved;
        /** 等待期间目标范围内新失败数 */
        long failed;
        /** 等待期间目标范围内新丢弃数 */
        long dropped;
        /** 等待增量中的未知结果数。 */
        long unknown;
        /** 注册时累计计数；注册后只累计目标范围内结果。 */
        long targetSaved, targetFailed, targetDropped, targetUnknown;
        /** 结束方式；null 表示尚未结束（最终按 COMPLETED 处理） */
        FlushResult.Outcome outcome;

        /** 按 seq ≤ target 的终态累计计数 */
        void count(FinalState state) {
            if (state == FinalState.SAVED) {
                saved++;
                targetSaved++;
            } else if (state == FinalState.FAILED) {
                failed++;
                targetFailed++;
            } else if (state == FinalState.DROPPED) {
                dropped++;
                targetDropped++;
            } else {
                unknown++;
                targetUnknown++;
            }
        }
    }

    private final IStorage storage;
    private final IExceptionStorage exceptionStorage;
    private final int batchSize;
    private final long batchIntervalMillis;
    private final QueueFullPolicy queueFullPolicy;

    private final LinkedBlockingQueue<LogEntry> queue;
    private final Thread worker;
    /** worker 排空与平台数据库关闭共用同一存活期保护。 */
    private final DatabaseOperationGuard operationGuard;
    /** flush 等待与终态统计锁；与 stateLock 的顺序为 stateLock → flushLock */
    private final Object flushLock = new Object();
    /** submit 与 stopAccepting 同步锁，保证 check-then-act 原子性 */
    private final Object stateLock = new Object();

    /** 引擎状态相关字段（volatile 保证可见性） */
    private volatile boolean running = true;
    /** 是否已开始关闭（停止接收） */
    private volatile boolean shutdownStarted = false;
    /** 已准入但仍在投递队列的调用数；stateLock 保护，关闭时仍须排空。 */
    private int activeOffers;
    private final AtomicLong warningCount = new AtomicLong(0);
    private volatile LogStatus status = new LogStatus(LogStatus.State.NORMAL, "init", 0L);
    /** 最近一次写入失败或等待超时描述；非 null 时通过 getStatus 暴露 */
    private volatile String lastWarningMessage = null;

    // ===== 接收序号与终态跟踪（均由 flushLock 保护）=====
    /** 下一个接收序号；分配与 pendingTotal 更新同在 flushLock 内，保证 flush 快照一致 */
    private long nextSeqValue = 0L;
    /** 最近一次已分配的接收序号（flush 读取的目标上界） */
    private long lastAssignedSeq = 0L;
    /** 已分配未终态的记录总数 */
    private long pendingTotal = 0L;
    /** 尚未终态的实际序号；删除成功才允许计数，避免空洞和重复落账。 */
    private final TreeSet<Long> pendingSeqs = new TreeSet<>();
    /** 活跃 flush 等待者 */
    private final List<FlushWaiter> waiters = new ArrayList<>();
    /** 累计终态计数（全部接收记录） */
    private long totalSaved = 0L;
    private long totalFailed = 0L;
    private long totalDropped = 0L;
    /** 累计结果未知数，包含无法确认提交状态的写入。 */
    private long totalUnknown = 0L;

    /**
     * 构造引擎
     * @param storage 日志存储
     * @param exceptionStorage 异常存储
     * @param queueCapacity 队列容量
     * @param queueFullPolicy 队列满策略
     * @param batchSize 批量阈值
     * @param batchIntervalMillis 批量周期（毫秒）
     */
    public AsyncLoggerEngine(IStorage storage, IExceptionStorage exceptionStorage,
                             int queueCapacity, QueueFullPolicy queueFullPolicy,
                             int batchSize, long batchIntervalMillis) {
        this(storage, exceptionStorage, queueCapacity, queueFullPolicy, batchSize, batchIntervalMillis,
                new DatabaseOperationGuard());
    }
    /** 平台管理器注入同一数据库保护，在 worker 启动前登记排空权。 */
    public AsyncLoggerEngine(IStorage storage, IExceptionStorage exceptionStorage,
                             int queueCapacity, QueueFullPolicy queueFullPolicy,
                             int batchSize, long batchIntervalMillis,
                             DatabaseOperationGuard operationGuard) {
        if (storage == null) {
            throw new NullPointerException("storage == null");
        }
        if (exceptionStorage == null) {
            throw new NullPointerException("exceptionStorage == null");
        }
        if (queueCapacity <= 0) {
            queueCapacity = 4096;
        }
        if (queueFullPolicy == null) {
            queueFullPolicy = QueueFullPolicy.DROP_OLDEST;
        }
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (batchIntervalMillis <= 0) {
            batchIntervalMillis = 1000L;
        }
        this.operationGuard = operationGuard;
        this.storage = storage;
        this.exceptionStorage = exceptionStorage;
        this.batchSize = batchSize;
        this.batchIntervalMillis = batchIntervalMillis;
        this.queueFullPolicy = queueFullPolicy;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);

        // 启动工作线程（守护线程，JVM 退出不阻塞）
        this.worker = new Thread(() -> {
            try { workerLoop(); } finally { operationGuard.finishDrainThread(); }
        }, "log-record-engine");
        operationGuard.registerDrainThread(worker);
        this.worker.setDaemon(true);
        this.worker.start();
        this.status = new LogStatus(LogStatus.State.NORMAL, "engine started", 0L);
    }

    /** 准入时分配序号；可能阻塞的队列投递在状态锁外完成。 */
    @Override
    public void submit(LogRecord record) {
        if (record == null) return;
        // 条目所有权在准入成功后转移到引擎，关闭不能遗漏正在投递的条目。
        final LogEntry entry;
        synchronized (stateLock) {
            if (shutdownStarted) {
                warningCount.incrementAndGet();
                return;
            }
            entry = LogEntry.of(record, allocateSeqLocked());
            activeOffers++;
        }
        offerAccepted(entry);
    }

    /** 异常记录与普通日志共享准入和关闭边界。 */
    @Override
    public void submit(ExceptionRecord record) {
        if (record == null) return;
        // 保存准入时已分配的真实序号。
        final LogEntry entry;
        synchronized (stateLock) {
            if (shutdownStarted) {
                warningCount.incrementAndGet();
                return;
            }
            entry = LogEntry.of(record, allocateSeqLocked());
            activeOffers++;
        }
        offerAccepted(entry);
    }

    /** 在锁外投递已接收条目，并无条件归还投递计数。 */
    private void offerAccepted(LogEntry entry) {
        try {
            offerEntry(entry);
        } finally {
            synchronized (stateLock) { activeOffers--; }
        }
    }

    /**
     * 分配接收序号并登记未完成计数（调用方持有 stateLock，内部获取 flushLock）
     * <p>序号分配、lastAssignedSeq 发布与 pendingTotal 自增在同一 flushLock 临界区内完成，
     * 保证 flush 在 flushLock 下读到的目标上界与未完成计数相互一致</p>
     */
    private long allocateSeqLocked() {
        synchronized (flushLock) {
            lastAssignedSeq = ++nextSeqValue;
            pendingTotal++;
            pendingSeqs.add(lastAssignedSeq);
            return lastAssignedSeq;
        }
    }

    @Override
    public void flush() {
        // 兼容入口：默认 5 秒等待；超时或失败通过状态消息暴露，供 getLastWarning 类入口诊断
        FlushResult result = flush(5000L);
        if (!result.isAllPersisted()) {
            lastWarningMessage = "flush ended with " + result.getOutcome()
                    + ", failed=" + result.getTargetFailed()
                    + ", dropped=" + result.getTargetDropped()
                    + ", unknown=" + result.getTargetUnknown() + ", pending=" + result.getPending();
        }
    }

    @Override
    public FlushResult flush(long timeoutMillis) {
        long timeout = Math.max(0L, timeoutMillis);
        // 单调时钟经过量避免绝对期限相加溢出。
        long started = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeout);
        synchronized (flushLock) {
            FlushWaiter waiter = new FlushWaiter();
            // 快照目标上界与未完成数：此刻不存在序号大于目标的记录，
            // 因此 pendingTotal 即目标范围内的未完成数
            waiter.target = lastAssignedSeq;
            waiter.alreadyFinalized = waiter.target - pendingTotal;
            waiter.targetSaved = totalSaved;
            waiter.targetFailed = totalFailed;
            waiter.targetDropped = totalDropped;
            waiter.targetUnknown = totalUnknown;
            waiters.add(waiter);
            try {
                while (!pendingSeqs.isEmpty() && pendingSeqs.first() <= waiter.target) {
                    long remainingNanos = budgetNanos - (System.nanoTime() - started);
                    if (remainingNanos <= 0L) {
                        waiter.outcome = FlushResult.Outcome.TIMEOUT;
                        break;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(flushLock, remainingNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        waiter.outcome = FlushResult.Outcome.INTERRUPTED;
                        break;
                    }
                }
            } finally {
                waiters.remove(waiter);
            }
            if (waiter.outcome == null) {
                waiter.outcome = FlushResult.Outcome.COMPLETED;
            }
            long pendingAtEntry = waiter.target - waiter.alreadyFinalized;
            long pending = Math.max(0L,
                    pendingAtEntry - waiter.saved - waiter.failed - waiter.dropped - waiter.unknown);
            return new FlushResult(waiter.outcome, waiter.target, waiter.alreadyFinalized,
                    waiter.saved, waiter.failed, waiter.dropped, waiter.unknown, pending,
                    waiter.targetSaved, waiter.targetFailed, waiter.targetDropped, waiter.targetUnknown);
        }
    }

    @Override
    public void shutdown() {
        shutdown(5000L);
    }

    @Override
    public ShutdownResult shutdown(long timeoutMillis) {
        // 停止准入与等待共用总时限，零时限只发起关闭。
        long started = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        stopAccepting();
        long remaining = Math.max(0L, budget - (System.nanoTime() - started));
        return awaitShutdown(TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    @Override
    public void stopAccepting() {
        synchronized (stateLock) {
            if (shutdownStarted) {
                return;
            }
            shutdownStarted = true;
            running = false;
        }
        // 不在任意时刻中断数据库写入；worker 使用短轮询发现关闭状态。
    }

    /** 正超时才等待线程；零或负值只返回当前状态，避免 join(0) 无限等待。 */
    @Override
    public ShutdownResult awaitShutdown(long timeoutMillis) {
        long timeout = Math.max(0L, timeoutMillis);
        try {
            if (timeout > 0L && Thread.currentThread() != worker) worker.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean completed = !worker.isAlive();
        long pending;
        synchronized (flushLock) {
            pending = pendingTotal;
        }
        if (completed) {
            status = new LogStatus(LogStatus.State.SHUTDOWN, "engine shutdown",
                    warningCount.get());
        } else {
            lastWarningMessage = "engine shutdown timeout, worker still draining";
            status = new LogStatus(LogStatus.State.SHUTDOWN, lastWarningMessage,
                    warningCount.get());
        }
        return completed
                ? ShutdownResult.completed(pending, true)
                : ShutdownResult.timeout(pending, true);
    }

    @Override
    public boolean isTerminated() {
        return !worker.isAlive();
    }

    @Override
    public LogStatus getStatus() {
        String warn = lastWarningMessage;
        if (warn != null) {
            return new LogStatus(status.getState(), warn, warningCount.get());
        }
        return new LogStatus(status.getState(), status.getMessage(), warningCount.get());
    }

    // ===== 内部方法 =====

    /**
     * 按队列满策略投递已准入条目；调用方不得持有 stateLock。
     */
    private void offerEntry(LogEntry entry) {
        boolean offered = queue.offer(entry);
        if (offered) {
            return;
        }
        // 队列满，按策略处理；被丢弃的记录置为 DROPPED 终态并唤醒等待者
        switch (queueFullPolicy) {
            case DROP_OLDEST:
                // 丢弃最旧一条腾出空间
                LogEntry dropped = queue.poll();
                warningCount.incrementAndGet();
                if (dropped != null) {
                    finalizeSeq(dropped.seq(), FinalState.DROPPED);
                }
                if (!queue.offer(entry)) {
                    // 极端并发下腾位失败：当前条目按丢弃终态处理
                    finalizeSeq(entry.seq(), FinalState.DROPPED);
                }
                break;
            case DROP_NEWEST:
                // 丢弃当前条目
                warningCount.incrementAndGet();
                finalizeSeq(entry.seq(), FinalState.DROPPED);
                break;
            case BLOCK:
                try {
                    // 阻塞等待，最多 1 秒
                    if (!queue.offer(entry, 1000L, TimeUnit.MILLISECONDS)) {
                        warningCount.incrementAndGet();
                        finalizeSeq(entry.seq(), FinalState.DROPPED);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    warningCount.incrementAndGet();
                    finalizeSeq(entry.seq(), FinalState.DROPPED);
                }
                break;
            default:
                warningCount.incrementAndGet();
                finalizeSeq(entry.seq(), FinalState.DROPPED);
                break;
        }
        // 告警计数超阈值时降级
        if (warningCount.get() > 1000 && status.getState() == LogStatus.State.NORMAL) {
            status = new LogStatus(LogStatus.State.DEGRADED,
                    "too many warnings, queue may be overloaded", warningCount.get());
        }
    }

    /**
     * 工作线程主循环
     */
    private void workerLoop() {
        // 实际条目保留类型和序号，DROP_OLDEST 造成的空洞不会被重新计数。
        List<LogEntry> entries = new ArrayList<>(batchSize);
        while (hasWork()) {
            try {
                // 关闭最多等待一个短轮询周期；不以中断打断正在提交的存储。
                LogEntry first = queue.poll(Math.min(batchIntervalMillis, 50L), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                entries.add(first);
                queue.drainTo(entries, batchSize - 1);
                flushEntries(entries);
            } catch (InterruptedException e) {
                // 内部唤醒不取消已接收记录，继续检查关闭与排空状态。
            } catch (Throwable t) {
                recordWriteWarning("worker", entries.size(), 0, t);
                for (LogEntry entry : entries) finalizeSeq(entry.seq(), FinalState.UNKNOWN);
            } finally {
                entries.clear();
            }
        }
    }

    /** 关闭后仍等待已经准入的投递调用完成，避免队列暂时为空时提前退出。 */
    private boolean hasWork() {
        synchronized (stateLock) {
            return running || activeOffers > 0 || !queue.isEmpty();
        }
    }

    /** 按存储类型分组，但保留每个输入的真实序号和原始下标。 */
    private void flushEntries(List<LogEntry> entries) {
        // 两个子批次分别上报结果，目标外的另一类失败不能污染目标内成功。
        List<LogRecord> logs = new ArrayList<>();
        List<ExceptionRecord> exceptions = new ArrayList<>();
        List<Long> logSeqs = new ArrayList<>();
        List<Long> exceptionSeqs = new ArrayList<>();
        for (LogEntry entry : entries) {
            if (entry.isLog()) {
                logs.add(entry.logRecord());
                logSeqs.add(entry.seq());
            } else {
                exceptions.add(entry.exceptionRecord());
                exceptionSeqs.add(entry.seq());
            }
        }
        if (!logs.isEmpty()) {
            // null 表示整批 writeBatch 已正常返回，全部确认保存。
            Throwable failure = null;
            try { storage.writeBatch(logs); } catch (Throwable t) { failure = t; }
            finishBatch(logSeqs, failure);
        }
        if (!exceptions.isEmpty()) {
            Throwable failure = null;
            try { exceptionStorage.writeBatch(exceptions); } catch (Throwable t) { failure = t; }
            finishBatch(exceptionSeqs, failure);
        }
    }

    /** 按逐条结果落账；旧式部分计数仅在完整覆盖子批次时保留汇总。 */
    private void finishBatch(List<Long> seqs, Throwable failure) {
        // 精确结果数组与输入下标一一对应；无可靠结果时保持 UNKNOWN。
        BatchWriteException batchFailure = failure instanceof BatchWriteException
                ? (BatchWriteException) failure : null;
        BatchWriteException.ItemOutcome[] items = batchFailure == null
                ? null : batchFailure.getItemOutcomes();
        int saved = batchFailure == null ? 0 : batchFailure.getSaved();
        boolean validCounts = batchFailure != null && batchFailure.getAttempted() == seqs.size()
                && saved >= 0 && saved <= seqs.size();
        if (failure != null) recordWriteWarning("writeBatch", seqs.size(), saved, failure);
        synchronized (flushLock) {
            if (failure != null && items == null && validCounts && saved > 0 && saved < seqs.size()) {
                finishAggregateLocked(seqs, saved);
                return;
            }
            for (int i = 0; i < seqs.size(); i++) {
                // 普通异常不能证明提交未发生；仅明确的存储结果可以标记失败。
                FinalState state = FinalState.UNKNOWN;
                if (failure == null) state = FinalState.SAVED;
                else if (items != null && items.length == seqs.size()) {
                    state = items[i] == BatchWriteException.ItemOutcome.SAVED ? FinalState.SAVED
                            : items[i] == BatchWriteException.ItemOutcome.FAILED ? FinalState.FAILED
                            : FinalState.UNKNOWN;
                } else if (items == null && validCounts) {
                    state = saved == seqs.size() ? FinalState.SAVED : FinalState.FAILED;
                }
                finalizeSeqLocked(seqs.get(i), state);
            }
        }
    }

    /** 旧式异常只知道整批数量；切分目标的等待者全部计 UNKNOWN，绝不猜测条目归属。 */
    private void finishAggregateLocked(List<Long> seqs, int saved) {
        // 正常路径中本批条目都在 pending；重复调用时只终结仍待定的部分为 UNKNOWN。
        boolean completeSet = pendingSeqs.containsAll(seqs);
        if (!completeSet) {
            for (long seq : seqs) finalizeSeqLocked(seq, FinalState.UNKNOWN);
            return;
        }
        for (long seq : seqs) pendingSeqs.remove(seq);
        pendingTotal -= seqs.size();
        totalSaved += saved;
        totalFailed += seqs.size() - saved;
        for (FlushWaiter waiter : waiters) {
            // 目标内数量只由真实序号计算，不使用连续区间。
            int covered = 0;
            for (long seq : seqs) if (seq <= waiter.target) covered++;
            if (covered == seqs.size()) {
                waiter.saved += saved;
                waiter.targetSaved += saved;
                waiter.failed += covered - saved;
                waiter.targetFailed += covered - saved;
            } else {
                waiter.unknown += covered;
                waiter.targetUnknown += covered;
            }
        }
        flushLock.notifyAll();
    }

    /** 记录写入失败，保留原始错误类型和已确认保存数量。 */
    private void recordWriteWarning(String where, int attempted, long saved, Throwable failure) {
        warningCount.incrementAndGet();
        lastWarningMessage = where + " failed: saved " + saved + "/" + attempted
                + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /** 在统计锁中终结一个实际接收序号。 */
    private void finalizeSeq(long seq, FinalState state) {
        synchronized (flushLock) { finalizeSeqLocked(seq, state); }
    }

    /** 删除 pending 成功才计数，保证每条接收记录最多到达一次终态。 */
    private void finalizeSeqLocked(long seq, FinalState state) {
        if (!pendingSeqs.remove(seq)) return;
        pendingTotal--;
        if (state == FinalState.SAVED) totalSaved++;
        else if (state == FinalState.FAILED) totalFailed++;
        else if (state == FinalState.DROPPED) totalDropped++;
        else totalUnknown++;
        for (FlushWaiter waiter : waiters) {
            if (seq <= waiter.target) waiter.count(state);
        }
        flushLock.notifyAll();
    }
}
