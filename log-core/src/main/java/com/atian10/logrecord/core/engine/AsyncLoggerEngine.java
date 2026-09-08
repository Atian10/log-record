package com.atian10.logrecord.core.engine;

import com.atian10.logrecord.core.BatchWriteException;
import com.atian10.logrecord.core.ILoggerEngine;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步日志引擎
 * <p>
 * 单工作线程 + {@link LinkedBlockingQueue} 实现异步写入，不阻塞业务线程。
 * 攒批策略：达到 {@code batchSize} 或 {@code batchIntervalMillis} 周期触发落盘。
 * 队列满时按 {@link QueueFullPolicy} 处理。
 * </p>
 * <p>
 * <b>flush 等待边界</b>：每条成功接收的记录分配单调递增的接收序号（seq）。
 * {@link #flush(long)} 只等待调用时刻目标序号及之前的未完成记录，之后新增的记录
 * 不延长本次等待。等待条件是"目标范围内全部记录到达终态（保存/失败/丢弃）"，
 * 而不是"队列为空"，因此最后一批尚未提交时不会提前返回，完全空闲时立即完成。
 * 结果与失败通过 {@link FlushResult} 与 {@link #getStatus()} 暴露。
 * </p>
 * <p>
 * <b>写入失败传递</b>：存储 {@code writeBatch} 抛出的异常（含
 * {@link BatchWriteException} 携带的已保存数量）被计入 failed 并记录告警，
 * 引擎不在业务线程抛出。批量降级重试由存储层负责，引擎只保留一层计数。
 * </p>
 * <p>
 * <b>关闭与排空</b>：{@link #stopAccepting()} 停止接收并唤醒工作线程；
 * 队列剩余条目由唯一工作线程在退出前排空（{@link #drainAndFlushRemaining}），
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
        DROPPED
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
        /** 结束方式；null 表示尚未结束（最终按 COMPLETED 处理） */
        FlushResult.Outcome outcome;

        /** 按 seq ≤ target 的终态累计计数 */
        void count(FinalState state) {
            if (state == FinalState.SAVED) {
                saved++;
            } else if (state == FinalState.FAILED) {
                failed++;
            } else {
                dropped++;
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
    /** flush 等待与终态统计锁；与 stateLock 的顺序为 stateLock → flushLock */
    private final Object flushLock = new Object();
    /** submit 与 stopAccepting 同步锁，保证 check-then-act 原子性 */
    private final Object stateLock = new Object();

    /** 引擎状态相关字段（volatile 保证可见性） */
    private volatile boolean running = true;
    /** 是否已开始关闭（停止接收） */
    private volatile boolean shutdownStarted = false;
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
    /** 所有 seq ≤ watermark 的记录均已到达终态 */
    private long watermark = 0L;
    /** 已终态但序号大于 watermark 的空洞（如被丢弃策略移除的记录），值为终态 */
    private final Map<Long, FinalState> holes = new HashMap<>();
    /** 活跃 flush 等待者 */
    private final List<FlushWaiter> waiters = new ArrayList<>();
    /** 累计终态计数（全部接收记录） */
    private long totalSaved = 0L;
    private long totalFailed = 0L;
    private long totalDropped = 0L;

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
        this.storage = storage;
        this.exceptionStorage = exceptionStorage;
        this.batchSize = batchSize;
        this.batchIntervalMillis = batchIntervalMillis;
        this.queueFullPolicy = queueFullPolicy;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);

        // 启动工作线程（守护线程，JVM 退出不阻塞）
        this.worker = new Thread(this::workerLoop, "log-record-engine");
        this.worker.setDaemon(true);
        this.worker.start();
        this.status = new LogStatus(LogStatus.State.NORMAL, "engine started", 0L);
    }

    @Override
    public void submit(LogRecord record) {
        synchronized (stateLock) {
            if (shutdownStarted) {
                warningCount.incrementAndGet();
                return;
            }
            offerEntry(LogEntry.of(record, allocateSeqLocked()));
        }
    }

    @Override
    public void submit(ExceptionRecord record) {
        synchronized (stateLock) {
            if (shutdownStarted) {
                warningCount.incrementAndGet();
                return;
            }
            offerEntry(LogEntry.of(record, allocateSeqLocked()));
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
            return lastAssignedSeq;
        }
    }

    @Override
    public void flush() {
        // 兼容入口：默认 5 秒等待；超时或失败通过状态消息暴露，供 getLastWarning 类入口诊断
        FlushResult result = flush(5000L);
        if (!result.isAllPersisted()) {
            lastWarningMessage = "flush ended with " + result.getOutcome()
                    + ", failed=" + result.getFailed()
                    + ", dropped=" + result.getDropped()
                    + ", pending=" + result.getPending();
        }
    }

    @Override
    public FlushResult flush(long timeoutMillis) {
        long timeout = Math.max(0L, timeoutMillis);
        // 单调时钟计算期限，避免系统时间调整影响等待
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        synchronized (flushLock) {
            FlushWaiter waiter = new FlushWaiter();
            // 快照目标上界与未完成数：此刻不存在序号大于目标的记录，
            // 因此 pendingTotal 即目标范围内的未完成数
            waiter.target = lastAssignedSeq;
            waiter.alreadyFinalized = waiter.target - pendingTotal;
            waiters.add(waiter);
            try {
                while (watermark < waiter.target) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
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
                    pendingAtEntry - waiter.saved - waiter.failed - waiter.dropped);
            return new FlushResult(waiter.outcome, waiter.target, waiter.alreadyFinalized,
                    waiter.saved, waiter.failed, waiter.dropped, pending);
        }
    }

    @Override
    public void shutdown() {
        shutdown(5000L);
    }

    @Override
    public ShutdownResult shutdown(long timeoutMillis) {
        stopAccepting();
        return awaitShutdown(timeoutMillis);
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
        // 唤醒工作线程的周期等待，使其退出循环并在退出前排空队列
        worker.interrupt();
    }

    @Override
    public ShutdownResult awaitShutdown(long timeoutMillis) {
        long timeout = Math.max(0L, timeoutMillis);
        try {
            worker.join(timeout);
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
     * 按队列满策略投递条目（调用方持有 stateLock）
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
        List<LogRecord> logBatch = new ArrayList<>(batchSize);
        List<ExceptionRecord> exceptionBatch = new ArrayList<>(batchSize);
        try {
            while (running) {
                LogEntry first = queue.poll(batchIntervalMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    // 超时无数据，进入下一轮
                    continue;
                }
                logBatch.clear();
                exceptionBatch.clear();
                // 批次内序号连续（队列保序，丢弃只发生在队列头部形成空洞）
                long firstSeq = first.seq();
                long lastSeq = firstSeq;
                collectEntry(first, logBatch, exceptionBatch);
                // 继续非阻塞地取直到达 batchSize 或队列为空
                while (logBatch.size() + exceptionBatch.size() < batchSize) {
                    LogEntry next = queue.poll();
                    if (next == null) {
                        break;
                    }
                    collectEntry(next, logBatch, exceptionBatch);
                    lastSeq = next.seq();
                }
                flushBatchRange(firstSeq, lastSeq, logBatch, exceptionBatch);
            }
        } catch (InterruptedException e) {
            // stopAccepting 的中断：退出循环，进入排空
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // 工作线程异常不应崩溃
            warningCount.incrementAndGet();
            status = new LogStatus(LogStatus.State.ERROR,
                    "worker crashed: " + t.getMessage(), warningCount.get());
        }
        // 唯一排空入口：worker 退出前把队列剩余条目落盘，不依赖调用线程兜底
        drainAndFlushRemaining();
    }

    private void collectEntry(LogEntry entry, List<LogRecord> logBatch,
                              List<ExceptionRecord> exceptionBatch) {
        if (entry.isLog()) {
            logBatch.add(entry.logRecord());
        } else if (entry.isException()) {
            exceptionBatch.add(entry.exceptionRecord());
        }
    }

    /**
     * 落盘一个连续序号区间的批次并落终态
     * <p>批量降级重试由存储层负责（见 {@link BatchWriteException} 契约），
     * 引擎只按异常携带的数量区分保存与失败，不再逐条回退。</p>
     *
     * @param firstSeq 区间首个序号
     * @param lastSeq 区间最后一个序号
     * @param logBatch 日志子批次
     * @param exceptionBatch 异常子批次
     */
    private void flushBatchRange(long firstSeq, long lastSeq,
                                 List<LogRecord> logBatch,
                                 List<ExceptionRecord> exceptionBatch) {
        long saved = 0L;
        long failed = 0L;
        if (!logBatch.isEmpty()) {
            try {
                storage.writeBatch(logBatch);
                saved += logBatch.size();
            } catch (Throwable t) {
                long s = extractSavedCount(t, logBatch.size());
                saved += s;
                failed += logBatch.size() - s;
                recordWriteWarning("storage.writeBatch", logBatch.size(), s, t);
            }
        }
        if (!exceptionBatch.isEmpty()) {
            try {
                exceptionStorage.writeBatch(exceptionBatch);
                saved += exceptionBatch.size();
            } catch (Throwable t) {
                long s = extractSavedCount(t, exceptionBatch.size());
                saved += s;
                failed += exceptionBatch.size() - s;
                recordWriteWarning("exceptionStorage.writeBatch", exceptionBatch.size(), s, t);
            }
        }
        // 区间终态按数量落账：逐条归属不可知，失败优先记在区间较旧一侧，
        // 使并发 flush 的目标边界偏向计入失败而非漏报
        synchronized (flushLock) {
            long failedRemaining = failed;
            for (long seq = firstSeq; seq <= lastSeq; seq++) {
                FinalState state = failedRemaining > 0L
                        ? FinalState.FAILED : FinalState.SAVED;
                if (state == FinalState.FAILED) {
                    failedRemaining--;
                }
                finalizeSeqLocked(seq, state);
            }
        }
    }

    /**
     * 从写入异常中提取已确认保存数量（越界收敛到 [0, attempted]）
     */
    private long extractSavedCount(Throwable t, int attempted) {
        if (t instanceof BatchWriteException) {
            int saved = ((BatchWriteException) t).getSaved();
            return Math.max(0, Math.min(saved, attempted));
        }
        return 0L;
    }

    /**
     * 记录写入失败告警
     */
    private void recordWriteWarning(String where, int attempted, long saved, Throwable t) {
        warningCount.incrementAndGet();
        lastWarningMessage = where + " failed: saved " + saved + "/" + attempted
                + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /**
     * 关闭时由工作线程落盘队列剩余条目
     */
    private void drainAndFlushRemaining() {
        List<LogRecord> logBatch = new ArrayList<>();
        List<ExceptionRecord> exceptionBatch = new ArrayList<>();
        long firstSeq = -1L;
        long lastSeq = -1L;
        LogEntry entry;
        while ((entry = queue.poll()) != null) {
            if (firstSeq < 0L) {
                firstSeq = entry.seq();
            }
            lastSeq = entry.seq();
            collectEntry(entry, logBatch, exceptionBatch);
        }
        if (firstSeq >= 0L) {
            flushBatchRange(firstSeq, lastSeq, logBatch, exceptionBatch);
        }
    }

    /**
     * 将单个序号置为终态并唤醒等待者（调用方不持有 flushLock）
     */
    private void finalizeSeq(long seq, FinalState state) {
        if (seq <= 0L) {
            // 外部构造的条目（seq=0）不参与等待边界
            return;
        }
        synchronized (flushLock) {
            finalizeSeqLocked(seq, state);
        }
    }

    /**
     * 将单个序号置为终态（调用方持有 flushLock）
     * <p>推进 watermark、登记空洞、累计总数、更新等待者计数并唤醒</p>
     */
    private void finalizeSeqLocked(long seq, FinalState state) {
        pendingTotal--;
        if (state == FinalState.SAVED) {
            totalSaved++;
        } else if (state == FinalState.FAILED) {
            totalFailed++;
        } else {
            totalDropped++;
        }
        if (seq == watermark + 1L) {
            // 连续到达：推进水位线并吞并紧随其后的空洞
            watermark = seq;
            Long holeKey = watermark + 1L;
            while (holes.containsKey(holeKey)) {
                holes.remove(holeKey);
                watermark++;
                holeKey++;
            }
        } else {
            // 序号在水位线之前存在缺口：登记为空洞，待缺口补齐后合并
            holes.put(seq, state);
        }
        for (FlushWaiter w : waiters) {
            if (seq <= w.target) {
                w.count(state);
            }
        }
        flushLock.notifyAll();
    }
}
