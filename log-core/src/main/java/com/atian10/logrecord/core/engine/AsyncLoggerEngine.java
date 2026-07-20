package com.atian10.logrecord.core.engine;

import com.atian10.logrecord.core.ILoggerEngine;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

import java.util.ArrayList;
import java.util.List;
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
 * 线程安全：submit 可在任意线程调用；flush/shutdown 同步等待。
 * 引擎关闭后 submit 静默丢弃并计入 warningCount。
 * </p>
 */
public final class AsyncLoggerEngine implements ILoggerEngine {

    private final IStorage storage;
    private final IExceptionStorage exceptionStorage;
    private final int batchSize;
    private final long batchIntervalMillis;
    private final QueueFullPolicy queueFullPolicy;

    private final LinkedBlockingQueue<LogEntry> queue;
    private final Thread worker;
    private final Object flushLock = new Object();
    /** submit 与 shutdown 同步锁，保证 check-then-act 原子性 */
    private final Object stateLock = new Object();

    /** 引擎状态相关字段（volatile 保证可见性） */
    private volatile boolean running = true;
    private volatile boolean shutdown = false;
    private final AtomicLong warningCount = new AtomicLong(0);
    private volatile LogStatus status = new LogStatus(LogStatus.State.NORMAL, "init", 0L);
    /** flush 完成版本号：worker 每完成一轮 batch 自增；flush 据此判断是否真正落盘 */
    private volatile long flushVersion = 0L;

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
            if (shutdown) {
                warningCount.incrementAndGet();
                return;
            }
            offerEntry(LogEntry.of(record));
        }
    }

    @Override
    public void submit(ExceptionRecord record) {
        synchronized (stateLock) {
            if (shutdown) {
                warningCount.incrementAndGet();
                return;
            }
            offerEntry(LogEntry.of(record));
        }
    }

    @Override
    public void flush() {
        if (shutdown) {
            return;
        }
        // 等待 worker 完成一轮 batch（flushVersion 变化）且队列为空，确保真正落盘
        synchronized (flushLock) {
            try {
                long observedVersion = flushVersion;
                long deadline = System.currentTimeMillis() + 5000L;
                // 条件：队列非空 或 版本号未变化（说明 worker 未处理新批次）
                while ((!queue.isEmpty() || flushVersion == observedVersion)
                        && System.currentTimeMillis() < deadline) {
                    flushLock.wait(50);
                    // 若队列已空且版本号已变化，认为已落盘
                    if (queue.isEmpty() && flushVersion != observedVersion) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void shutdown() {
        synchronized (stateLock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            running = false;
        }
        // 唤醒工作线程使其检查 shutdown 标志
        worker.interrupt();
        try {
            worker.join(5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 关闭前把队列剩余条目尽量落盘
        drainAndFlushRemaining();
        status = new LogStatus(LogStatus.State.SHUTDOWN, "engine shutdown",
                warningCount.get());
    }

    @Override
    public LogStatus getStatus() {
        return new LogStatus(status.getState(), status.getMessage(),
                warningCount.get());
    }

    // ===== 内部方法 =====

    /**
     * 按队列满策略投递条目
     */
    private void offerEntry(LogEntry entry) {
        boolean offered = queue.offer(entry);
        if (offered) {
            return;
        }
        // 队列满，按策略处理
        switch (queueFullPolicy) {
            case DROP_OLDEST:
                // 丢弃最旧一条腾出空间
                queue.poll();
                if (queue.offer(entry)) {
                    warningCount.incrementAndGet();
                }
                break;
            case DROP_NEWEST:
                // 丢弃当前条目
                warningCount.incrementAndGet();
                break;
            case BLOCK:
                try {
                    // 阻塞等待，最多 1 秒
                    if (!queue.offer(entry, 1000L, TimeUnit.MILLISECONDS)) {
                        warningCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    warningCount.incrementAndGet();
                }
                break;
            default:
                warningCount.incrementAndGet();
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
                collectEntry(first, logBatch, exceptionBatch);
                // 继续非阻塞地取直到达 batchSize 或队列为空
                while (logBatch.size() + exceptionBatch.size() < batchSize) {
                    LogEntry next = queue.poll();
                    if (next == null) {
                        break;
                    }
                    collectEntry(next, logBatch, exceptionBatch);
                }
                flushBatches(logBatch, exceptionBatch);
                // 完成一轮 batch，自增 flushVersion 并唤醒可能等待 flush 的线程
                flushVersion++;
                synchronized (flushLock) {
                    flushLock.notifyAll();
                }
            }
        } catch (InterruptedException e) {
            // shutdown 中断，正常退出
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // 工作线程异常不应崩溃
            warningCount.incrementAndGet();
            status = new LogStatus(LogStatus.State.ERROR,
                    "worker crashed: " + t.getMessage(), warningCount.get());
        }
        // 退出前尝试落盘剩余
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
     * 批量落盘
     */
    private void flushBatches(List<LogRecord> logBatch,
                              List<ExceptionRecord> exceptionBatch) {
        try {
            if (!logBatch.isEmpty()) {
                storage.writeBatch(logBatch);
            }
        } catch (Throwable t) {
            warningCount.incrementAndGet();
            // 批量失败降级为逐条写入
            for (LogRecord r : logBatch) {
                try {
                    storage.write(r);
                } catch (Throwable ignored) {
                    // 逐条写入也失败，记录告警后跳过该条
                    warningCount.incrementAndGet();
                }
            }
        }
        try {
            if (!exceptionBatch.isEmpty()) {
                exceptionStorage.writeBatch(exceptionBatch);
            }
        } catch (Throwable t) {
            warningCount.incrementAndGet();
            for (ExceptionRecord r : exceptionBatch) {
                try {
                    exceptionStorage.write(r);
                } catch (Throwable ignored) {
                    warningCount.incrementAndGet();
                }
            }
        }
    }

    /**
     * 关闭时落盘队列剩余条目
     */
    private void drainAndFlushRemaining() {
        List<LogRecord> logBatch = new ArrayList<>();
        List<ExceptionRecord> exceptionBatch = new ArrayList<>();
        LogEntry entry;
        while ((entry = queue.poll()) != null) {
            collectEntry(entry, logBatch, exceptionBatch);
        }
        if (!logBatch.isEmpty() || !exceptionBatch.isEmpty()) {
            flushBatches(logBatch, exceptionBatch);
        }
    }
}
