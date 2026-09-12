package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.core.engine.ShutdownResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * LogManager 关闭生命周期测试（T-09 / ISSUE-09）
 * <p>
 * 覆盖：A 关闭 → B 初始化 → A 再次关闭时不清空 B、重复关闭幂等、
 * 关闭结果缓存、关闭后可重新初始化。
 * </p>
 * <p>
 * 注意：LogManager 使用静态单例，本类各方法须顺序执行，
 * 避免与其他用例共享静态状态产生顺序依赖。
 * </p>
 */
public class LogManagerLifecycleTest {

    @Test
    public void shutdownLifecycle_isIdempotentAndKeepsNewerInstance() {
        // 1. 初始化 A 并写入
        FakeStorage storageA = new FakeStorage();
        LogManager a = LogManager.init(config(storageA));
        try {
            a.i("Test", "instance A before shutdown");
            FlushResult flushA = a.flush(2000L);
            assertTrue("flush of A should persist accepted records",
                    flushA.isAllPersisted());
            assertEquals(1, storageA.getWrittenRecords().size());

            // 2. 关闭 A：静态引用清除，允许重新初始化
            ShutdownResult shutdownA = a.shutdown(2000L);
            assertTrue(shutdownA.isFullyTerminated());
            assertTrue(a.isTerminated());
            assertFalse(LogManager.isInitialized());

            // 3. 初始化 B
            FakeStorage storageB = new FakeStorage();
            LogManager b = LogManager.init(config(storageB));
            assertTrue(LogManager.isInitialized());
            assertSame(b, LogManager.get());

            // 4. 旧实例 A 再次关闭：幂等，且不得清空新实例 B 的全局引用（ISSUE-09 核心断言）
            ShutdownResult shutdownAgain = a.shutdown(2000L);
            assertEquals(ShutdownResult.Outcome.COMPLETED, shutdownAgain.getOutcome());
            assertTrue(LogManager.isInitialized());
            assertSame(b, LogManager.get());

            // 5. B 仍可正常写入与排空
            b.i("Test", "instance B still works after old instance shutdown");
            FlushResult flushB = b.flush(2000L);
            assertTrue(flushB.isAllPersisted());
            assertEquals(1, storageB.getWrittenRecords().size());

            // 6. 重复关闭 B：幂等并返回缓存结果
            ShutdownResult first = b.shutdown(2000L);
            ShutdownResult second = b.shutdown(2000L);
            assertTrue(first.isFullyTerminated());
            assertEquals(first.getOutcome(), second.getOutcome());
            assertFalse(LogManager.isInitialized());
        } finally {
            // 兜底清理静态状态，避免影响其他测试类
            if (LogManager.isInitialized()) {
                LogManager.get().shutdown(2000L);
            }
        }
    }

    /** 零时限只发起关闭；活动操作归还后由后台完成，第二次调用应更新结果。 */
    @Test public void zeroTimeoutWaitsForOperationWithoutClosingItEarly() {
        DatabaseOperationGuard guard = new DatabaseOperationGuard();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        LogManager manager = LogManager.init(LogConfig.builder().storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage()).consoleEnabled(false)
                .databaseOwner(guard, closes::incrementAndGet).build());
        try {
            try (DatabaseOperationGuard.Scope operation = guard.enter()) {
                long started = System.nanoTime();
                ShutdownResult pending = manager.shutdown(0L);
                assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000L);
                assertFalse(pending.isFullyTerminated());
                assertEquals(0, closes.get());
            }
            assertTrue(manager.shutdown(5000L).isFullyTerminated());
            assertEquals(1, closes.get());
            assertTrue(manager.shutdown(0L).isFullyTerminated());
            assertFalse(LogManager.isInitialized());
        } finally { manager.shutdown(5000L); }
    }

    /**
     * 构建最小可用配置：内存存储、关闭控制台与方法行捕获、清理策略保持默认禁用
     */
    private LogConfig config(FakeStorage storage) {
        return LogConfig.builder()
                .storage(storage)
                .exceptionStorage(new FakeExceptionStorage())
                .consoleEnabled(false)
                .captureMethodLine(false)
                .build();
    }
}
