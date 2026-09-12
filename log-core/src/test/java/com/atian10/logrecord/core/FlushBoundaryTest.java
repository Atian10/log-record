package com.atian10.logrecord.core;

import com.atian10.logrecord.core.engine.AsyncLoggerEngine;
import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.core.engine.QueueFullPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** flush 目标、历史结果及非连续序号的回归源码；本轮未执行。 */
public class FlushBoundaryTest {
    /** 专用等待线程与受测引擎；每个场景在 finally 中先释放存储屏障。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AsyncLoggerEngine engine;

    /** 确保断言失败也不遗留 worker 或等待线程。 */
    @After public void tearDown() {
        if (engine != null) engine.shutdown(5000L);
        executor.shutdownNow();
    }

    /** 第一批固定为一条，后续记录由测试在线程屏障期间一次排入。 */
    private static class GatedStorage extends FakeStorage {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        int batches;
        /** 第一批等待许可，后续批次可由用例定义成功、部分失败或未知。 */
        @Override public void writeBatch(List<LogRecord> records) {
            if (++batches == 1) {
                entered.countDown();
                try {
                    if (!release.await(5L, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                super.writeBatch(records);
            } else {
                writeLater(records);
            }
        }
        /** 默认后续批次正常保存。 */
        void writeLater(List<LogRecord> records) { super.writeBatch(records); }
    }

    /** 空闲立即完成；排空后的第二次 flush 增量为零而目标累计仍保留。 */
    @Test public void idleAndHistoricalSuccess() {
        engine = newEngine(new FakeStorage(), 100);
        assertTrue(engine.flush(0L).isAllPersisted());
        engine.submit(record("first"));
        assertTrue(engine.flush(5000L).isAllPersisted());
        FlushResult again = engine.flush(0L);
        assertEquals(0L, again.getSaved());
        assertEquals(1L, again.getTargetSaved());
        assertTrue(again.isAllPersisted());
    }

    /** 旧式部分汇总覆盖整个目标时可精确计数；历史失败不能被第二次 flush 掩盖。 */
    @Test public void historicalPartialFailureIsRetained() throws Exception {
        GatedStorage storage = new GatedStorage() {
            @Override void writeLater(List<LogRecord> records) {
                assertEquals(3, records.size());
                throw new BatchWriteException(3, 2, "partial", null);
            }
        };
        begin(storage, 100);
        try {
            engine.submit(record("2"));
            engine.submit(record("3"));
            engine.submit(record("4"));
            Future<FlushResult> waiting = registeredFlush();
            storage.release.countDown();
            FlushResult first = waiting.get(5L, TimeUnit.SECONDS);
            assertEquals(3L, first.getTargetSaved());
            assertEquals(1L, first.getTargetFailed());
            assertFalse(first.isAllPersisted());
            FlushResult again = engine.flush(0L);
            assertEquals(0L, again.getFailed());
            assertEquals(1L, again.getTargetFailed());
            assertFalse(again.isAllPersisted());
        } finally { storage.release.countDown(); }
    }

    /** 普通异常没有未提交证据，必须保留 UNKNOWN。 */
    @Test public void unclassifiedWriteFailureRemainsUnknown() {
        engine = newEngine(new FakeStorage() {
            @Override public void writeBatch(List<LogRecord> records) { throw new IllegalStateException("unknown commit"); }
        }, 100);
        engine.submit(record("unknown"));
        FlushResult first = engine.flush(5000L);
        assertEquals(1L, first.getTargetUnknown());
        assertEquals(0L, first.getTargetFailed());
        assertFalse(engine.flush(0L).isAllPersisted());
    }

    /** DROP_OLDEST 制造 seq2 空洞；逐条结果必须匹配 seq3/seq4，不能二次终结 seq2。 */
    @Test public void holesAndPerItemOutcomesAreCountedExactlyOnce() throws Exception {
        GatedStorage storage = new GatedStorage() {
            @Override void writeLater(List<LogRecord> records) {
                assertEquals(2, records.size());
                throw new BatchWriteException(new BatchWriteException.ItemOutcome[]{
                        BatchWriteException.ItemOutcome.SAVED, BatchWriteException.ItemOutcome.FAILED}, "items", null);
            }
        };
        begin(storage, 2);
        try {
            engine.submit(record("drop-2"));
            engine.submit(record("saved-3"));
            engine.submit(record("failed-4"));
            storage.release.countDown();
            FlushResult result = engine.flush(5000L);
            assertEquals(4L, result.getTargetSeq());
            assertEquals(2L, result.getTargetSaved());
            assertEquals(1L, result.getTargetFailed());
            assertEquals(1L, result.getTargetDropped());
            assertEquals(0L, result.getPending());
            assertFalse(engine.flush(0L).isAllPersisted());
        } finally { storage.release.countDown(); }
    }

    /** 旧式部分汇总跨过当前 flush 目标时，目标子集无法归属，按 UNKNOWN 返回。 */
    @Test public void partialAggregateStraddlingTargetIsUnknown() throws Exception {
        GatedStorage storage = new GatedStorage() {
            @Override void writeLater(List<LogRecord> records) {
                assertEquals(3, records.size());
                throw new BatchWriteException(3, 2, "partial", null);
            }
        };
        begin(storage, 100);
        try {
            engine.submit(record("2"));
            engine.submit(record("3"));
            Future<FlushResult> waiting = registeredFlush();
            engine.submit(record("after-target"));
            storage.release.countDown();
            FlushResult result = waiting.get(5L, TimeUnit.SECONDS);
            assertEquals(3L, result.getTargetSeq());
            assertEquals(1L, result.getTargetSaved());
            assertEquals(2L, result.getTargetUnknown());
            assertEquals(0L, result.getPending());
            assertFalse(result.isAllPersisted());
        } finally { storage.release.countDown(); }
    }

    /** 在途写入不提前完成；零时限关闭立即返回，之后自然排空。 */
    @Test public void pendingFlushAndZeroShutdownAreBounded() throws Exception {
        GatedStorage storage = new GatedStorage();
        begin(storage, 100);
        try {
            FlushResult pending = engine.flush(0L);
            assertEquals(FlushResult.Outcome.TIMEOUT, pending.getOutcome());
            assertEquals(1L, pending.getPending());
            long started = System.nanoTime();
            engine.shutdown(0L);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000L);
            assertFalse(engine.isTerminated());
        } finally { storage.release.countDown(); }
        engine.shutdown(5000L);
        assertTrue(engine.isTerminated());
        assertTrue(engine.flush(0L).isAllPersisted());
    }

    /** 中断等待返回 INTERRUPTED，保持中断标志且不改变目标记录状态。 */
    @Test public void interruptedWaitDoesNotFinalizeRecords() throws Exception {
        GatedStorage storage = new GatedStorage();
        begin(storage, 100);
        try {
            Thread.currentThread().interrupt();
            FlushResult result = engine.flush(5000L);
            assertEquals(FlushResult.Outcome.INTERRUPTED, result.getOutcome());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1L, result.getPending());
        } finally {
            Thread.interrupted();
            storage.release.countDown();
        }
    }

    /** 启动一条在途记录，让后续批次的集合不依赖线程调度概率。 */
    private void begin(GatedStorage storage, int capacity) throws Exception {
        engine = newEngine(storage, capacity);
        engine.submit(record("first"));
        assertTrue(storage.entered.await(5L, TimeUnit.SECONDS));
    }

    /** 只观察已注册等待者；不修改私有状态，不用固定 sleep 猜测注册时间。 */
    private Future<FlushResult> registeredFlush() throws Exception {
        Field lockField = AsyncLoggerEngine.class.getDeclaredField("flushLock");
        Field waitersField = AsyncLoggerEngine.class.getDeclaredField("waiters");
        lockField.setAccessible(true);
        waitersField.setAccessible(true);
        Object lock = lockField.get(engine);
        Future<FlushResult> future = executor.submit(() -> engine.flush(5000L));
        long started = System.nanoTime();
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5L)) {
            synchronized (lock) {
                if (!((List<?>) waitersField.get(engine)).isEmpty()) return future;
            }
            Thread.yield();
        }
        throw new AssertionError("flush waiter was not registered");
    }

    /** 使用足够大的批次上限，第一条屏障期间排入的记录会作为完整一批取出。 */
    private AsyncLoggerEngine newEngine(IStorage storage, int capacity) {
        return new AsyncLoggerEngine(storage, new FakeExceptionStorage(), capacity,
                QueueFullPolicy.DROP_OLDEST, 100, 60000L);
    }

    /** 最小日志记录。 */
    private LogRecord record(String message) {
        return new LogRecord(1L, LogLevel.INFO, "T", "tag", "test", 1L,
                null, 0, null, false, message, null);
    }
}
