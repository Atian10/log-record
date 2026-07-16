package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.engine.AsyncLoggerEngine;
import com.atian10.logrecord.core.engine.LogStatus;
import com.atian10.logrecord.core.engine.QueueFullPolicy;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * AsyncLoggerEngine 测试
 * <p>
 * 覆盖：submit/flush 落盘、submit 异常记录、shutdown 后丢弃、队列满策略、状态查询。
 * </p>
 */
public class AsyncLoggerEngineTest {

    @Test
    public void submitLogRecord_thenFlush_isWrittenToStorage() throws Exception {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        AsyncLoggerEngine engine = newEngine(storage, exStorage, 100,
                QueueFullPolicy.DROP_OLDEST, 100, 1000L);

        try {
            LogRecord r = newLogRecord(1L, LogLevel.INFO, "msg1");
            engine.submit(r);
            engine.flush();
            // 等待工作线程处理（flush 已等待队列清空，但写入是异步进行的）
            waitForRecords(storage, 1, 2000);
            assertEquals(1, storage.getWrittenRecords().size());
            assertEquals("msg1", storage.getWrittenRecords().get(0).getMessage());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void submitBatch_thenFlush_allWritten() throws Exception {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        AsyncLoggerEngine engine = newEngine(storage, exStorage, 1000,
                QueueFullPolicy.DROP_OLDEST, 10, 1000L);

        try {
            for (int i = 0; i < 35; i++) {
                engine.submit(newLogRecord(i, LogLevel.INFO, "msg" + i));
            }
            engine.flush();
            waitForRecords(storage, 35, 3000);
            assertEquals(35, storage.getWrittenRecords().size());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void submitException_thenFlush_isWrittenToExceptionStorage() throws Exception {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        AsyncLoggerEngine engine = newEngine(storage, exStorage, 100,
                QueueFullPolicy.DROP_OLDEST, 100, 1000L);

        try {
            ExceptionRecord r = new ExceptionRecord(1L, "java.lang.NullPointerException",
                    "npe", "stack", "tag1");
            engine.submit(r);
            engine.flush();
            waitForExceptionRecords(exStorage, 1, 2000);
            assertEquals(1, exStorage.getWrittenRecords().size());
            assertEquals("java.lang.NullPointerException",
                    exStorage.getWrittenRecords().get(0).getExceptionClass());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void submitAfterShutdown_isDroppedWithWarning() {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        AsyncLoggerEngine engine = newEngine(storage, exStorage, 100,
                QueueFullPolicy.DROP_OLDEST, 100, 1000L);

        engine.shutdown();

        // shutdown 后 submit 应被丢弃
        engine.submit(newLogRecord(1L, LogLevel.INFO, "after-shutdown"));
        long warnings = engine.getStatus().getWarningCount();
        assertTrue("warnings should be > 0 after submit post shutdown", warnings > 0);
        assertEquals(0, storage.getWrittenRecords().size());
    }

    @Test
    public void shutdown_drainsRemaining() throws Exception {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        // 用长批量周期避免工作线程自动落盘
        AsyncLoggerEngine engine = newEngine(storage, exStorage, 1000,
                QueueFullPolicy.DROP_OLDEST, 1000, 60000L);

        for (int i = 0; i < 10; i++) {
            engine.submit(newLogRecord(i, LogLevel.INFO, "msg" + i));
        }
        engine.shutdown();
        // shutdown 应落盘所有剩余
        assertEquals(10, storage.getWrittenRecords().size());
    }

    @Test
    public void queueFull_dropNewest_dropsNewAndCountsWarning() throws Exception {
        // 用慢消费 Storage 制造队列堆积：writeBatch 阻塞 200ms
        FakeStorage storage = new FakeStorage() {
            @Override
            public synchronized void writeBatch(List<LogRecord> records) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.writeBatch(records);
            }
        };
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        // 队列容量=2，batchSize=1（每条触发一次落盘），周期=60s
        AsyncLoggerEngine engine = newEngine(storage, exStorage, 2,
                QueueFullPolicy.DROP_NEWEST, 1, 60000L);

        try {
            // 快速投递 10 条，工作线程因 writeBatch 阻塞导致队列溢出
            for (int i = 0; i < 10; i++) {
                engine.submit(newLogRecord(i, LogLevel.INFO, "msg" + i));
            }
            // 等待工作线程处理一两个批次让告警计数稳定
            Thread.sleep(300);
            long warnings = engine.getStatus().getWarningCount();
            assertTrue("warnings should be > 0 with DROP_NEWEST on queue overflow, got " + warnings,
                    warnings > 0);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void getStatus_afterInit_returnsNormal() {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        AsyncLoggerEngine engine = newEngine(storage, exStorage, 100,
                QueueFullPolicy.DROP_OLDEST, 100, 1000L);
        try {
            LogStatus status = engine.getStatus();
            assertEquals(LogStatus.State.NORMAL, status.getState());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void constructor_nullStorage_throwsNpe() {
        try {
            new AsyncLoggerEngine(null, new FakeExceptionStorage(),
                    100, QueueFullPolicy.DROP_OLDEST, 100, 1000L);
            fail("null storage should throw NullPointerException");
        } catch (NullPointerException expected) {
            // 期望异常
        }
    }

    @Test
    public void constructor_nullExceptionStorage_throwsNpe() {
        try {
            new AsyncLoggerEngine(new FakeStorage(), null,
                    100, QueueFullPolicy.DROP_OLDEST, 100, 1000L);
            fail("null exception storage should throw NullPointerException");
        } catch (NullPointerException expected) {
            // 期望异常
        }
    }

    @Test
    public void storageException_doesNotCrashEngine() throws Exception {
        // 用一个会抛异常的 Storage
        IStorage badStorage = new IStorage() {
            @Override
            public void write(LogRecord record) {
                throw new RuntimeException("write fail");
            }
            @Override
            public void writeBatch(List<LogRecord> records) {
                throw new RuntimeException("writeBatch fail");
            }
            @Override
            public List<LogRecord> query(LogQuery query) {
                return new ArrayList<>();
            }
            @Override
            public LogStatistics statistics(LogQuery query) {
                return new LogStatistics(0, null, null, null);
            }
            @Override
            public int clean(CleanPolicy policy) {
                return 0;
            }
            @Override
            public int cleanBefore(long timestamp) {
                return 0;
            }
            @Override
            public int cleanByCount(int keepCount) {
                return 0;
            }
            @Override
            public long getRecordCount() {
                return 0;
            }
            @Override
            public long getDbSizeBytes() {
                return 0;
            }
            @Override
            public long count(LogQuery query) {
                return 0;
            }
        };
        AsyncLoggerEngine engine = new AsyncLoggerEngine(badStorage, new FakeExceptionStorage(),
                100, QueueFullPolicy.DROP_OLDEST, 100, 1000L);
        try {
            engine.submit(newLogRecord(1L, LogLevel.ERROR, "boom"));
            engine.flush();
            // 引擎不应崩溃
            LogStatus status = engine.getStatus();
            // 可能是 NORMAL 或 DEGRADED（告警计数增长），但不应是 ERROR
            assertFalse("engine should not be in ERROR state for storage exception",
                    status.getState() == LogStatus.State.ERROR
                            && status.getMessage().contains("worker crashed"));
        } finally {
            engine.shutdown();
        }
    }

    // ===== 辅助方法 =====

    private AsyncLoggerEngine newEngine(IStorage storage, IExceptionStorage exStorage,
                                        int queueCapacity, QueueFullPolicy policy,
                                        int batchSize, long batchInterval) {
        return new AsyncLoggerEngine(storage, exStorage, queueCapacity, policy,
                batchSize, batchInterval);
    }

    private LogRecord newLogRecord(long ts, LogLevel level, String msg) {
        return new LogRecord(ts, level, "T", "tag",
                Thread.currentThread().getName(), Thread.currentThread().getId(),
                null, 0, null, false, msg, null);
    }

    private void waitForRecords(FakeStorage storage, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (storage.getWrittenRecords().size() < expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private void waitForExceptionRecords(FakeExceptionStorage storage, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (storage.getWrittenRecords().size() < expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
