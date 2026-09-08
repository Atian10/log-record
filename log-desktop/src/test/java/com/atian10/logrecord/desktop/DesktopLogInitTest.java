package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Desktop 初始化资源归属测试（T-10 / ISSUE-10）
 * <p>
 * 覆盖：重复初始化失败时不替换已发布 helper、候选资源被释放、原实例保持可用、
 * 关闭后 helper 释放且可重新初始化。
 * </p>
 * <p>
 * 注意：依赖 LogManager 静态单例，本类只包含一个顺序执行的测试方法。
 * </p>
 */
public class DesktopLogInitTest {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    @After
    public void tearDown() {
        if (LogManager.isInitialized()) {
            DesktopLogInit.shutdown();
        } else if (DesktopLogInit.getHelper() != null) {
            DesktopLogInit.shutdown();
        }
    }

    @Test
    public void duplicateInit_failsWithoutReplacingOwner_andAllowsReinitAfterShutdown() throws Exception {
        File dbA = tempDir.newFile("t-owner-a.db");
        File dbB = tempDir.newFile("t-owner-b.db");

        // 1. 用数据库 A 初始化成功
        LogManager a = DesktopLogInit.init(dbA.getAbsolutePath(), configBuilder());
        JdbcHelper helperA = DesktopLogInit.getHelper();
        assertNotNull("helper should be published after successful init", helperA);
        a.i("Test", "A before duplicate init");
        FlushResult flushA = a.flush(2000L);
        assertTrue("A should still persist records", flushA.isAllPersisted());

        // 2. 尝试用数据库 B 重复初始化：核心层拒绝
        try {
            DesktopLogInit.init(dbB.getAbsolutePath(), configBuilder());
            fail("expected IllegalStateException for duplicate init");
        } catch (IllegalStateException expected) {
            // 期望异常
        }

        // 3. 静态 helper 未被候选 B 替换，候选资源已释放（ISSUE-10 核心断言）
        assertSame("published helper must not be replaced by failed init candidate",
                helperA, DesktopLogInit.getHelper());
        assertFalse("A's helper must stay open", helperA.isClosed());
        // 候选 B 的连接已关闭：其数据库文件可以删除（Windows 下打开的 SQLite 文件不可删）
        assertTrue("failed candidate db file should be released and deletable",
                dbB.delete());

        // 4. 原实例 A 仍可正常写入
        a.i("Test", "A after duplicate init failure");
        FlushResult flushAgain = a.flush(2000L);
        assertTrue(flushAgain.isAllPersisted());
        assertEquals(2L, a.getLogCount());

        // 5. 关闭后 helper 释放，可重新初始化
        DesktopLogInit.shutdown();
        assertFalse(LogManager.isInitialized());
        assertTrue("helper should be closed after shutdown", helperA.isClosed());

        LogManager b = DesktopLogInit.init(dbA.getAbsolutePath(), configBuilder());
        assertNotSame(a, b);
        b.i("Test", "second life");
        FlushResult flushB = b.flush(2000L);
        assertTrue(flushB.isAllPersisted());
        assertEquals(3L, b.getLogCount());
        DesktopLogInit.shutdown();
    }

    /**
     * 构建最小桌面配置（控制台关闭，避免测试输出噪音）
     */
    private LogConfig.Builder configBuilder() {
        return LogConfig.builder()
                .consoleEnabled(false)
                .captureMethodLine(false)
                .versionTag("t");
    }
}
