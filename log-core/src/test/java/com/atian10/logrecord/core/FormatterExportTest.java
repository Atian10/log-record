package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.core.export.ExportEncoding;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.export.Exporter;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * formatter 更新与导出快照测试（T-04 / ISSUE-08）
 * <p>
 * 覆盖：Exporter 按次 formatter 参数生效与回退、更新前导出用旧 formatter、
 * 更新后新导出用新 formatter、导出进行中更新配置时当前文件保持单一格式、
 * getFormatter 读取入口与导出行为一致。
 * </p>
 */
public class FormatterExportTest {

    /** 单次断言等待的通用时限（秒） */
    private static final int AWAIT_SECONDS = 5;

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    /**
     * 输出 {@code [tag] message} 的简单 formatter，便于断言输出来源
     */
    static final class TagFormatter implements IFormatter {
        private final String tag;

        TagFormatter(String tag) {
            this.tag = tag;
        }

        @Override
        public String format(LogRecord record, ExportFormat format) {
            return "[" + tag + "] " + record.getMessage();
        }

        @Override
        public String format(ExceptionRecord record, ExportFormat format) {
            return "[" + tag + "] " + record.getExceptionMessage();
        }
    }

    /**
     * 首次 format 阻塞的 formatter：用于在导出进行中修改配置的并发场景
     */
    static final class GatedFormatter implements IFormatter {
        private final CountDownLatch enteredFirst = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        /** format 调用计数 */
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String format(LogRecord record, ExportFormat format) {
            if (invocations.incrementAndGet() == 1) {
                enteredFirst.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "[G] " + record.getMessage();
        }

        @Override
        public String format(ExceptionRecord record, ExportFormat format) {
            return "[G] " + record.getExceptionMessage();
        }
    }

    @Test
    public void exporter_perCallFormatter_usedForWholeExport() throws Exception {
        FakeStorage storage = newStorageWithLogs(3);
        // 构造时 formatter 为 A，本次导出指定 B：全程使用 B
        Exporter exporter = new Exporter(storage, new FakeExceptionStorage(),
                new TagFormatter("A"));
        File file = tempDir.newFile("per-call.txt");

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8, new TagFormatter("B"),
                file.getAbsolutePath(), null);

        assertEquals(3, count);
        List<String> lines = readLines(file);
        assertEquals(3, lines.size());
        for (String line : lines) {
            assertTrue("line should use per-call formatter: " + line, line.startsWith("[B] "));
        }
    }

    @Test
    public void exporter_nullPerCallFormatter_fallsBackToConstructorFormatter() throws Exception {
        FakeStorage storage = newStorageWithLogs(2);
        Exporter exporter = new Exporter(storage, new FakeExceptionStorage(),
                new TagFormatter("A"));
        File file = tempDir.newFile("fallback.txt");

        exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8, null,
                file.getAbsolutePath(), null);

        for (String line : readLines(file)) {
            assertTrue(line.startsWith("[A] "));
        }
    }

    @Test
    public void logManager_formatterUpdate_appliesToNextExport_andMidExportSnapshotKeepsUniform() throws Exception {
        // 依赖 LogManager 静态单例：本方法内部顺序执行并在 finally 清理
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            FakeStorage storage = new FakeStorage();
            TagFormatter initial = new TagFormatter("A");
            LogManager manager = LogManager.init(LogConfig.builder()
                    .storage(storage)
                    .exceptionStorage(new FakeExceptionStorage())
                    .formatter(initial)
                    .consoleEnabled(false)
                    .captureMethodLine(false)
                    .build());
            try {
                manager.i("Test", "m1");
                manager.i("Test", "m2");
                manager.i("Test", "m3");
                FlushResult flush = manager.flush(2000L);
                assertTrue("flush should persist before export", flush.isAllPersisted());

                // 更新前导出：使用旧 formatter A
                File before = tempDir.newFile("before.txt");
                manager.exportLogs(LogQuery.builder().build(), ExportFormat.TXT,
                        before.getAbsolutePath(), null);
                assertAllLinesStartWith(before, "[A] ");

                // 运行时更新为 B：getFormatter 与后续导出使用新值
                TagFormatter updated = new TagFormatter("B");
                manager.getConfigUpdater().updateFormatter(updated);
                assertSame(updated, manager.getFormatter());

                File after = tempDir.newFile("after.txt");
                manager.exportLogs(LogQuery.builder().build(), ExportFormat.TXT,
                        after.getAbsolutePath(), null);
                assertAllLinesStartWith(after, "[B] ");

                // 导出进行中更新配置：当前文件保持单一格式（GatedFormatter 快照）
                GatedFormatter gated = new GatedFormatter();
                manager.getConfigUpdater().updateFormatter(gated);
                File during = tempDir.newFile("during.txt");
                Future<Integer> exporting = executor.submit(() ->
                        manager.exportLogs(LogQuery.builder().build(), ExportFormat.TXT,
                                during.getAbsolutePath(), null));
                assertTrue("first format should be entered",
                        gated.enteredFirst.await(AWAIT_SECONDS, TimeUnit.SECONDS));
                // 导出进行中把配置切换为 D：不影响本次文件
                manager.getConfigUpdater().updateFormatter(new TagFormatter("D"));
                gated.release.countDown();
                exporting.get(AWAIT_SECONDS, TimeUnit.SECONDS);
                assertAllLinesStartWith(during, "[G] ");
                assertFalse(readFile(during).contains("[D]"));

                // 下一次导出使用 D
                File next = tempDir.newFile("next.txt");
                manager.exportLogs(LogQuery.builder().build(), ExportFormat.TXT,
                        next.getAbsolutePath(), null);
                assertAllLinesStartWith(next, "[D] ");
            } finally {
                manager.shutdown(2000L);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ===== 辅助方法 =====

    /**
     * 构建带 n 条日志的内存存储
     */
    private FakeStorage newStorageWithLogs(int n) {
        FakeStorage storage = new FakeStorage();
        for (int i = 0; i < n; i++) {
            storage.write(new LogRecord(1L, LogLevel.INFO,
                    "T", "tag", Thread.currentThread().getName(),
                    Thread.currentThread().getId(), null, 0, null, false, "msg" + i, null));
        }
        return storage;
    }

    /**
     * 断言文件每行都以指定前缀开始
     */
    private void assertAllLinesStartWith(File file, String prefix) throws IOException {
        List<String> lines = readLines(file);
        assertFalse("export should have lines", lines.isEmpty());
        for (String line : lines) {
            assertTrue("line should start with " + prefix + " but was: " + line,
                    line.startsWith(prefix));
        }
    }

    private List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : readLines(file)) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
