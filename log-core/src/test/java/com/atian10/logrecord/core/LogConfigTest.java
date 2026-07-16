package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.engine.QueueFullPolicy;
import com.atian10.logrecord.core.export.ExportEncoding;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * LogConfig 测试
 * <p>
 * 覆盖：默认值、链式设置、不可变性、缺省 storage 抛异常、builderFrom 拷贝。
 * </p>
 */
public class LogConfigTest {

    @Test
    public void builder_defaults_areApplied() {
        LogConfig config = LogConfig.builder()
                .storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage())
                .build();

        assertEquals(4096, config.getQueueCapacity());
        assertEquals(QueueFullPolicy.DROP_OLDEST, config.getQueueFullPolicy());
        assertEquals(100, config.getBatchSize());
        assertEquals(1000L, config.getBatchIntervalMillis());
        assertFalse(config.isConsoleEnabled());
        assertFalse(config.isCaptureMethodLine());
        assertNull(config.getVersionTag());
        assertEquals(ExportEncoding.UTF_8, config.getExportEncoding());
        // 默认清理策略为禁用
        assertFalse(config.getCleanPolicy().isEnabled());
        assertFalse(config.getExceptionCleanPolicy().isEnabled());
    }

    @Test
    public void builder_chain_overridesDefaults() {
        LogConfig config = LogConfig.builder()
                .storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage())
                .queueCapacity(1024)
                .queueFullPolicy(QueueFullPolicy.BLOCK)
                .batchSize(50)
                .batchIntervalMillis(500L)
                .consoleEnabled(true)
                .captureMethodLine(true)
                .versionTag("v2.0")
                .exportEncoding(ExportEncoding.GBK)
                .cleanPolicy(CleanPolicy.builder().enable(true).keepDays(7).build())
                .exceptionCleanPolicy(CleanPolicy.builder().enable(true).keepDays(14).build())
                .build();

        assertEquals(1024, config.getQueueCapacity());
        assertEquals(QueueFullPolicy.BLOCK, config.getQueueFullPolicy());
        assertEquals(50, config.getBatchSize());
        assertEquals(500L, config.getBatchIntervalMillis());
        assertTrue(config.isConsoleEnabled());
        assertTrue(config.isCaptureMethodLine());
        assertEquals("v2.0", config.getVersionTag());
        assertEquals(ExportEncoding.GBK, config.getExportEncoding());
        assertTrue(config.getCleanPolicy().isEnabled());
        assertEquals(7, config.getCleanPolicy().getKeepDays());
        assertTrue(config.getExceptionCleanPolicy().isEnabled());
        assertEquals(14, config.getExceptionCleanPolicy().getKeepDays());
    }

    @Test
    public void builder_storageNull_throwsIllegalState() {
        try {
            LogConfig.builder().build();
            fail("storage null should throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // 期望异常
        }
    }

    @Test
    public void builder_exceptionStorageNull_throwsIllegalState() {
        try {
            LogConfig.builder()
                    .storage(new FakeStorage())
                    .build();
            fail("exceptionStorage null should throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // 期望异常
        }
    }

    @Test
    public void builderFrom_copiesAllFields() {
        LogConfig source = LogConfig.builder()
                .storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage())
                .queueCapacity(2048)
                .batchSize(50)
                .versionTag("v1")
                .captureMethodLine(true)
                .cleanPolicy(CleanPolicy.builder().enable(true).keepDays(3).build())
                .build();

        LogConfig copy = LogConfig.builderFrom(source).build();

        assertEquals(source.getQueueCapacity(), copy.getQueueCapacity());
        assertEquals(source.getBatchSize(), copy.getBatchSize());
        assertEquals(source.getVersionTag(), copy.getVersionTag());
        assertEquals(source.isCaptureMethodLine(), copy.isCaptureMethodLine());
        assertEquals(source.getCleanPolicy().getKeepDays(), copy.getCleanPolicy().getKeepDays());
        // 同一 storage 引用（浅拷贝，符合不可变对象设计）
        assertTrue(source.getStorage() == copy.getStorage());
    }

    @Test
    public void builderFrom_thenModify_doesNotAffectSource() {
        LogConfig source = LogConfig.builder()
                .storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage())
                .versionTag("v1")
                .build();

        LogConfig modified = LogConfig.builderFrom(source)
                .versionTag("v2")
                .build();

        // 修改后的版本应改变
        assertEquals("v2", modified.getVersionTag());
        // 原配置不变（不可变对象）
        assertEquals("v1", source.getVersionTag());
    }
}
