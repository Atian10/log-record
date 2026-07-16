package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.config.LogConfigUpdater;
import com.atian10.logrecord.core.export.ExportEncoding;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * LogConfigUpdater 测试
 * <p>
 * 覆盖：CAS 修改动态配置项、原配置不可变、整体替换。
 * </p>
 */
public class LogConfigUpdaterTest {

    @Test
    public void get_returnsInitialConfig() {
        LogConfig initial = newConfig("v1");
        LogConfigUpdater updater = new LogConfigUpdater(initial);
        assertEquals("v1", updater.get().getVersionTag());
    }

    @Test
    public void constructor_nullConfig_throwsNpe() {
        try {
            new LogConfigUpdater(null);
            fail("null config should throw NullPointerException");
        } catch (NullPointerException expected) {
            // 期望异常
        }
    }

    @Test
    public void updateVersionTag_changesConfig() {
        LogConfig initial = newConfig("v1");
        LogConfigUpdater updater = new LogConfigUpdater(initial);
        updater.updateVersionTag("v2");
        assertEquals("v2", updater.get().getVersionTag());
    }

    @Test
    public void updateCaptureMethodLine_changesConfig() {
        LogConfig initial = newConfig("v1");
        LogConfigUpdater updater = new LogConfigUpdater(initial);
        assertFalse(updater.get().isCaptureMethodLine());

        updater.updateCaptureMethodLine(true);
        assertTrue(updater.get().isCaptureMethodLine());

        updater.updateCaptureMethodLine(false);
        assertFalse(updater.get().isCaptureMethodLine());
    }

    @Test
    public void updateConsoleEnabled_changesConfig() {
        LogConfigUpdater updater = new LogConfigUpdater(newConfig("v1"));
        updater.updateConsoleEnabled(true);
        assertTrue(updater.get().isConsoleEnabled());
    }

    @Test
    public void updateCleanPolicy_changesConfig() {
        LogConfigUpdater updater = new LogConfigUpdater(newConfig("v1"));
        CleanPolicy newPolicy = CleanPolicy.builder()
                .enable(true)
                .keepDays(14)
                .build();
        updater.updateCleanPolicy(newPolicy);
        assertTrue(updater.get().getCleanPolicy().isEnabled());
        assertEquals(14, updater.get().getCleanPolicy().getKeepDays());
    }

    @Test
    public void updateExceptionCleanPolicy_changesConfig() {
        LogConfigUpdater updater = new LogConfigUpdater(newConfig("v1"));
        CleanPolicy newPolicy = CleanPolicy.builder()
                .enable(true)
                .keepDays(30)
                .build();
        updater.updateExceptionCleanPolicy(newPolicy);
        assertEquals(30, updater.get().getExceptionCleanPolicy().getKeepDays());
    }

    @Test
    public void updateExportEncoding_changesConfig() {
        LogConfigUpdater updater = new LogConfigUpdater(newConfig("v1"));
        updater.updateExportEncoding(ExportEncoding.GBK);
        assertEquals(ExportEncoding.GBK, updater.get().getExportEncoding());
    }

    @Test
    public void set_replacesEntireConfig() {
        LogConfigUpdater updater = new LogConfigUpdater(newConfig("v1"));
        LogConfig replacement = LogConfig.builder()
                .storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage())
                .versionTag("replacement")
                .captureMethodLine(true)
                .build();
        updater.set(replacement);
        assertEquals("replacement", updater.get().getVersionTag());
        assertTrue(updater.get().isCaptureMethodLine());
    }

    @Test
    public void set_null_throwsNpe() {
        LogConfigUpdater updater = new LogConfigUpdater(newConfig("v1"));
        try {
            updater.set(null);
            fail("null set should throw NullPointerException");
        } catch (NullPointerException expected) {
            // 期望异常
        }
    }

    @Test
    public void multipleUpdates_areAppliedSequentially() {
        LogConfigUpdater updater = new LogConfigUpdater(newConfig("v1"));
        updater.updateVersionTag("v2");
        updater.updateCaptureMethodLine(true);
        updater.updateConsoleEnabled(true);
        updater.updateExportEncoding(ExportEncoding.UTF_16);

        LogConfig current = updater.get();
        assertEquals("v2", current.getVersionTag());
        assertTrue(current.isCaptureMethodLine());
        assertTrue(current.isConsoleEnabled());
        assertEquals(ExportEncoding.UTF_16, current.getExportEncoding());
    }

    private LogConfig newConfig(String versionTag) {
        return LogConfig.builder()
                .storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage())
                .versionTag(versionTag)
                .build();
    }
}
