package com.atian10.logrecord.core;

import com.atian10.logrecord.core.engine.AsyncLoggerEngine;
import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.core.engine.QueueFullPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * flush 等待边界测试（T-01 / ISSUE-01）
 * <p>
 * 覆盖：空闲调用立即完成、最后一批在途时不能提前返回、调用后新增记录不延长目标、
 * 写入失败与队列丢弃结束等待并计数、超时与中断结果。并发场景使用锁存器控制存储阻塞，
 * 不用 sleep 掩盖等待结果。
 * </p>
 */
public class FlushBoundaryTest {

    /** 单次断言等待的通用时限（秒） */
    private static final int AWAIT_SECONDS = 5;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AsyncLoggerEngine engine;

    @After
    public void tearDown() {
        if (engine != null) {
            engine.shutdown(5000L);
        }
        executor.shutdownNow();
    }

    @Test
    public void flush_idleEngine_returnsImmediatelyCompleted() {
        FakeStorage storage = new FakeStorage();
        engine = newEngine(storage, new FakeExceptionStorage(), 100, 100, 60000L);
        long startNanos = System.nanoTime();
        FlushResult result = engine.flush(5000L);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        // 空闲且无未完成记录：立即完成，不再等满超时（原缺陷为固定等待约 5 秒）
        assertEquals(FlushResult.Outcome.COMPLETED, result.getOutcome());
        assertEquals(0L, result.getTargetSeq());
        assertTrue("idle flush should return promptly, took " + elapsedMillis + "ms",
                elapsedMillis < 2000L);
    }

    @Test
    public void flush_lastBatchInFlight_doesNotReturnEarly() throws Exception {
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        FakeStorage storage = new FakeStorage() {
            @Override
            public synchronized void writeBatch(List<LogRecord> records) {
                enteredWrite.countDown();
                try {
                    releaseWrite.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.writeBatch(records);
            }
        };
        // batchSize=1：每条记录一批，制造"前一批版本已变、最后一批在途"的原缺陷场景
        engine = newEngine(storage, new FakeExceptionStorage(), 100, 1, 60000L);
        engine.submit(record("msg1"));
        engine.submit(record("msg2"));
        assertTrue("worker should enter first batch", enteredWrite.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        Future<FlushResult> flusher = executor.submit(() -> engine.flush(5000L));
        // 观察窗：确认最后一批未提交时 flush 未提前返回（原缺陷此时会因版本变化而返回）
        Thread.sleep(200);
        assertFalse("flush must not return while last batch is uncommitted", flusher.isDone());
        releaseWrite.countDown();
        FlushResult result = flusher.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(FlushResult.Outcome.COMPLETED, result.getOutcome());
        assertTrue(result.isAllPersisted());
        assertEquals(2L, result.getSaved());
        assertEquals(2, storage.getWrittenRecords().size());
    }

    @Test
    public void flush_subsequentSubmissions_doNotExtendWaitTarget() throws Exception {
        CountDownLatch enteredFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger batches = new AtomicInteger();
        FakeStorage storage = new FakeStorage() {
            @Override
            public void writeBatch(List<LogRecord> records) {
                if (batches.incrementAndGet() == 1) {
                    enteredFirst.countDown();
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    // 后续批次慢速但有限，用于观察目标边界不被晚到记录延长
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                super.writeBatch(records);
            }
        };
        engine = newEngine(storage, new FakeExceptionStorage(), 100, 1, 60000L);
        engine.submit(record("msg1"));
        assertTrue("worker should enter first batch", enteredFirst.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        Future<FlushResult> flusher = executor.submit(() -> engine.flush(5000L));
        // 等待 flush 完成注册（注册是纯内存操作，200ms 观察窗足够），再提交晚到记录
        Thread.sleep(200);
        engine.submit(record("msg2"));
        releaseFirst.countDown();
        FlushResult result = flusher.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        // 目标固定为 1：晚到的 msg2 不属于本次等待边界
        assertEquals(1L, result.getTargetSeq());
        assertEquals(FlushResult.Outcome.COMPLETED, result.getOutcome());
        assertTrue(result.isAllPersisted());
        assertEquals(1L, result.getSaved());
    }

    @Test
    public void flush_writeFailure_endsWaitWithFailedCount() {
        IStorage failing = new FakeStorage() {
            @Override
            public void writeBatch(List<LogRecord> records) {
                // 模拟存储层降级后仍部分失败：3 条中保存 2 条
                throw new BatchWriteException(records.size(), records.size() - 1, "partial failure", null);
            }
        };
        engine = newEngine(failing, new FakeExceptionStorage(), 100, 100, 60000L);
        engine.submit(record("msg1"));
        engine.submit(record("msg2"));
        engine.submit(record("msg3"));
        FlushResult result = engine.flush(5000L);
        // 失败结束等待但不代表成功：COMPLETED 且 failed=1、saved=2
        assertEquals(FlushResult.Outcome.COMPLETED, result.getOutcome());
        assertEquals(2L, result.getSaved());
        assertEquals(1L, result.getFailed());
        assertEquals(0L, result.getPending());
        assertFalse(result.isAllPersisted());
    }

    @Test
    public void flush_droppedRecord_endsWaitWithDroppedCount() throws Exception {
        CountDownLatch enteredFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        FakeStorage storage = new FakeStorage() {
            @Override
            public synchronized void writeBatch(List<LogRecord> records) {
                enteredFirst.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.writeBatch(records);
            }
        };
        // 队列容量 1：msg2 入队后 msg3 触发 DROP_OLDEST 丢弃 msg2
        engine = newEngine(storage, new FakeExceptionStorage(), 1, 1, 60000L);
        engine.submit(record("msg1"));
        assertTrue("worker should enter first batch", enteredFirst.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        engine.submit(record("msg2"));
        // 先注册等待（目标 2），再触发丢弃，使丢弃计入等待期间的终态统计
        Future<FlushResult> flusher = executor.submit(() -> engine.flush(5000L));
        Thread.sleep(200);
        engine.submit(record("msg3"));
        releaseFirst.countDown();
        FlushResult result = flusher.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        // 目标 2 条全部终态：seq1 保存 + seq2 丢弃；seq3 晚于目标不计入
        assertEquals(2L, result.getTargetSeq());
        assertEquals(FlushResult.Outcome.COMPLETED, result.getOutcome());
        assertEquals(1L, result.getDropped());
        assertEquals(1L, result.getSaved());
        assertFalse(result.isAllPersisted());
        assertEquals(2, storage.getWrittenRecords().size());
    }

    @Test
    public void flush_timeout_reportsPending() throws Exception {
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        FakeStorage storage = new FakeStorage() {
            @Override
            public synchronized void writeBatch(List<LogRecord> records) {
                enteredWrite.countDown();
                try {
                    releaseWrite.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.writeBatch(records);
            }
        };
        engine = newEngine(storage, new FakeExceptionStorage(), 100, 100, 60000L);
        engine.submit(record("msg1"));
        assertTrue("worker should enter batch", enteredWrite.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        FlushResult result = engine.flush(100L);
        assertEquals(FlushResult.Outcome.TIMEOUT, result.getOutcome());
        assertEquals(1L, result.getPending());
        // 释放阻塞让 tearDown 的 shutdown 可以完成排空
        releaseWrite.countDown();
    }

    @Test
    public void flush_interrupted_returnsInterruptedOutcome() throws Exception {
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        FakeStorage storage = new FakeStorage() {
            @Override
            public synchronized void writeBatch(List<LogRecord> records) {
                enteredWrite.countDown();
                try {
                    releaseWrite.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.writeBatch(records);
            }
        };
        engine = newEngine(storage, new FakeExceptionStorage(), 100, 100, 60000L);
        engine.submit(record("msg1"));
        assertTrue("worker should enter batch", enteredWrite.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        final java.util.concurrent.atomic.AtomicReference<FlushResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread flusher = new Thread(() -> outcome.set(engine.flush(10000L)), "flush-interrupt-test");
        flusher.start();
        Thread.sleep(200);
        // 中断等待线程：应返回 INTERRUPTED 结果并恢复中断标志，而不是继续等待
        flusher.interrupt();
        flusher.join(5000);
        releaseWrite.countDown();
        FlushResult result = outcome.get();
        assertEquals(FlushResult.Outcome.INTERRUPTED, result.getOutcome());
        assertEquals(1L, result.getPending());
    }

    @Test
    public void flush_afterShutdownDrain_returnsCompletedForAcceptedRecords() throws Exception {
        FakeStorage storage = new FakeStorage();
        engine = newEngine(storage, new FakeExceptionStorage(), 100, 100, 60000L);
        engine.submit(record("msg1"));
        // 关闭排空后，flush 对已接收记录立即给出终态结果
        engine.shutdown(5000L);
        FlushResult result = engine.flush(1000L);
        assertEquals(FlushResult.Outcome.COMPLETED, result.getOutcome());
        // msg1 在本次调用前已由排空保存：计入 alreadyFinalized 而非 saved
        assertEquals(1L, result.getAlreadyFinalized());
        assertEquals(0L, result.getSaved());
        assertEquals(0L, result.getPending());
        assertTrue(result.isAllPersisted());
        assertEquals(1, storage.getWrittenRecords().size());
    }

    // ===== 辅助方法 =====

    private AsyncLoggerEngine newEngine(IStorage storage, IExceptionStorage exStorage,
                                        int queueCapacity, int batchSize, long batchInterval) {
        return new AsyncLoggerEngine(storage, exStorage, queueCapacity,
                QueueFullPolicy.DROP_OLDEST, batchSize, batchInterval);
    }

    private LogRecord record(String msg) {
        return new LogRecord(1L, LogLevel.INFO, "T", "tag",
                Thread.currentThread().getName(), Thread.currentThread().getId(),
                null, 0, null, false, msg, null);
    }
}
