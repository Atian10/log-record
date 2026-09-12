package com.atian10.logrecord.core;

import com.atian10.logrecord.core.export.ExportCallback;
import com.atian10.logrecord.core.export.ExportEncoding;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.export.Exporter;
import com.atian10.logrecord.core.formatter.DefaultFormatter;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 导出失败契约测试（T-06 / ISSUE-04，core 部分）
 * <p>
 * 覆盖：不支持快照的存储明确失败、单条 formatter 失败终止导出、
 * 失败保留原目标与清理临时文件、发布失败保留原目标、回调自身抛错不影响结果
 * 也不触发相反终态、空集合发布空文件。
 * </p>
 */
public class ExportFailureContractTest {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    /** 回调终态计数：onSuccess/onFailure 各至多一次 */
    static final class CountingCallback implements ExportCallback {
        final AtomicInteger successCount = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        /** 回调内是否抛错（测试回调异常契约） */
        final boolean throwInCallbacks;

        CountingCallback() {
            this(false);
        }

        CountingCallback(boolean throwInCallbacks) {
            this.throwInCallbacks = throwInCallbacks;
        }

        @Override
        public void onProgress(int exported, int total) {
            if (throwInCallbacks) {
                throw new IllegalStateException("progress boom");
            }
        }

        @Override
        public void onSuccess(String filePath, int totalCount) {
            successCount.incrementAndGet();
            if (throwInCallbacks) {
                throw new IllegalStateException("success boom");
            }
        }

        @Override
        public void onFailure(Throwable error, int exportedCount) {
            failureCount.incrementAndGet();
            failure.set(error);
        }
    }

    /**
     * 对指定消息抛错的 formatter（模拟单条格式化失败）
     */
    static final class FailingMessageFormatter implements IFormatter {
        private final String triggerMessage;

        FailingMessageFormatter(String triggerMessage) {
            this.triggerMessage = triggerMessage;
        }

        @Override
        public String format(LogRecord record, ExportFormat format) {
            if (triggerMessage.equals(record.getMessage())) {
                throw new IllegalStateException("format boom: " + triggerMessage);
            }
            return record.getMessage();
        }

        @Override
        public String format(ExceptionRecord record, ExportFormat format) {
            return record.getExceptionMessage();
        }
    }

    @Test
    public void unsupportedSnapshotStorage_failsClearlyWithoutTargetCreation() throws Exception {
        IStorage unsupported = new FakeStorage() {
            @Override
            public IExportSnapshot<LogRecord> openExportSnapshot(LogQuery query) {
                // 模拟未适配的自定义存储：保持接口默认的不支持行为
                throw new UnsupportedOperationException(
                        "storage does not implement openExportSnapshot");
            }
        };
        unsupported.write(record("m1"));
        Exporter exporter = new Exporter(unsupported, new FakeExceptionStorage(),
                new DefaultFormatter());
        File target = tempDir.newFile("unsupported.txt");
        target.delete(); // 目标不存在，验证失败时不创建
        CountingCallback callback = new CountingCallback();

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                target.getAbsolutePath(), callback);

        assertEquals(0, count);
        assertEquals(1, callback.failureCount.get());
        assertEquals(0, callback.successCount.get());
        assertNotNull(callback.failure.get());
        assertTrue(callback.failure.get() instanceof UnsupportedOperationException);
        assertFalse("target must not be created on failure", target.exists());
        assertTrue("no temp files should remain", listExportTemps(target.getParentFile()).isEmpty());
    }

    @Test
    public void singleFormatterFailure_terminatesExportAndKeepsOriginalTarget() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.write(record("m1"));
        storage.write(record("m2"));
        storage.write(record("BOOM"));
        storage.write(record("m4"));
        Exporter exporter = new Exporter(storage, new FakeExceptionStorage(),
                new FailingMessageFormatter("BOOM"));
        File target = tempDir.newFile("formatter-fail.txt");
        writeString(target, "ORIGINAL-CONTENT");
        CountingCallback callback = new CountingCallback();

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                target.getAbsolutePath(), callback);

        assertEquals(0, count);
        assertEquals(1, callback.failureCount.get());
        assertEquals(0, callback.successCount.get());
        // 原目标不受损：仍是发布前的内容
        assertEquals("ORIGINAL-CONTENT", readFile(target));
        assertTrue("no temp files should remain", listExportTemps(target.getParentFile()).isEmpty());
    }

    @Test
    public void publishFailure_preservesOriginalAndCleansTempFiles() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.write(record("m1"));
        Exporter exporter = new Exporter(storage, new FakeExceptionStorage(),
                new DefaultFormatter());
        // 目标路径是非空目录：rename 与 nio 替换都会失败（空目录在 Windows 上可能被成功替换）
        File targetDir = tempDir.newFolder("publish-fail-target");
        writeString(new File(targetDir, "occupant.txt"), "occupant");
        CountingCallback callback = new CountingCallback();

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                targetDir.getAbsolutePath(), callback);

        assertEquals(0, count);
        assertEquals(1, callback.failureCount.get());
        assertEquals(0, callback.successCount.get());
        assertTrue("original directory must remain", targetDir.exists());
        assertTrue("no temp files should remain", listExportTemps(targetDir.getParentFile()).isEmpty());
    }

    @Test
    public void throwingCallbacks_doNotAffectResultNorTriggerOppositeTerminal() throws Exception {
        FakeStorage storage = new FakeStorage();
        for (int i = 0; i < 3; i++) {
            storage.write(record("m" + i));
        }
        Exporter exporter = new Exporter(storage, new FakeExceptionStorage(),
                new DefaultFormatter());
        File target = tempDir.newFile("callback-throw.txt");
        CountingCallback callback = new CountingCallback(true);

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                target.getAbsolutePath(), callback);

        // 回调抛错不影响导出结果，也不触发 onFailure
        assertEquals(3, count);
        assertEquals(1, callback.successCount.get());
        assertEquals(0, callback.failureCount.get());
        assertEquals(3, countLines(target));
        assertNotNull("callback error should be recorded", exporter.getLastCallbackError());
    }

    @Test
    public void emptyResultSet_publishesEmptyFileWithSuccessZero() throws Exception {
        FakeStorage storage = new FakeStorage();
        Exporter exporter = new Exporter(storage, new FakeExceptionStorage(),
                new DefaultFormatter());
        File target = tempDir.newFile("empty.txt");
        CountingCallback callback = new CountingCallback();

        int count = exporter.exportLogs(LogQuery.builder().keyword("nothing-matches").build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                target.getAbsolutePath(), callback);

        assertEquals(0, count);
        assertEquals(1, callback.successCount.get());
        assertEquals(0, callback.failureCount.get());
        assertTrue(target.exists());
        assertTrue(readFile(target).isEmpty());
        assertNull(exporter.getLastCallbackError());
    }

    /** count 和 close 同时失败时保留首错及 suppressed，且只发出一次失败终态。 */
    @Test public void countAndCloseFailurePreservePrimaryError() throws Exception {
        IllegalStateException countError = new IllegalStateException("count failed");
        IllegalStateException closeError = new IllegalStateException("close failed");
        AtomicInteger closed = new AtomicInteger();
        IStorage storage = new FakeStorage() {
            @Override public IExportSnapshot<LogRecord> openExportSnapshot(LogQuery query) {
                return new IExportSnapshot<LogRecord>() {
                    @Override public long getCapturedCount() { throw countError; }
                    @Override public List<LogRecord> nextBatch(int maxRows) { throw new AssertionError("must not read"); }
                    @Override public boolean isExhausted() { return false; }
                    @Override public void close() { closed.incrementAndGet(); throw closeError; }
                };
            }
        };
        File target = tempDir.newFile("count-close.txt");
        writeString(target, "ORIGINAL");
        CountingCallback callback = new CountingCallback();
        Exporter exporter = new Exporter(storage, new FakeExceptionStorage(), new DefaultFormatter());
        assertEquals(0, exporter.exportLogs(LogQuery.builder().build(), ExportFormat.TXT,
                ExportEncoding.UTF_8, target.getAbsolutePath(), callback));
        assertEquals(1, closed.get());
        assertEquals(1, callback.failureCount.get());
        assertEquals(0, callback.successCount.get());
        org.junit.Assert.assertSame(countError, callback.failure.get());
        org.junit.Assert.assertSame(closeError, countError.getSuppressed()[0]);
        assertEquals("ORIGINAL", readFile(target));
        assertTrue(listExportTemps(target.getParentFile()).isEmpty());
    }

    /** 提前返回空页不能发布截短文件，即使尚无 nextBatch 异常。 */
    @Test public void prematureEmptyPageFailsWithoutReplacingTarget() throws Exception {
        IStorage storage = new FakeStorage() {
            @Override public IExportSnapshot<LogRecord> openExportSnapshot(LogQuery query) {
                return new IExportSnapshot<LogRecord>() {
                    @Override public long getCapturedCount() { return 1L; }
                    @Override public List<LogRecord> nextBatch(int maxRows) { return java.util.Collections.emptyList(); }
                    @Override public boolean isExhausted() { return false; }
                    @Override public void close() { /* 本桩无外部资源。 */ }
                };
            }
        };
        File target = tempDir.newFile("short-page.txt");
        writeString(target, "ORIGINAL");
        CountingCallback callback = new CountingCallback();
        new Exporter(storage, new FakeExceptionStorage(), new DefaultFormatter()).exportLogs(
                LogQuery.builder().build(), ExportFormat.TXT, ExportEncoding.UTF_8,
                target.getAbsolutePath(), callback);
        assertEquals(1, callback.failureCount.get());
        assertEquals(0, callback.successCount.get());
        assertEquals("ORIGINAL", readFile(target));
        assertTrue(listExportTemps(target.getParentFile()).isEmpty());
    }

    // ===== 辅助方法 =====

    private LogRecord record(String message) {
        return new LogRecord(1L, LogLevel.INFO, "T", "tag",
                Thread.currentThread().getName(), Thread.currentThread().getId(),
                null, 0, null, false, message, null);
    }

    /**
     * 列出目标目录中的输出临时文件；系统临时目录中的快照不由此断言覆盖
     */
    private List<File> listExportTemps(File dir) {
        File[] files = dir.listFiles((FilenameFilter) (d, name) ->
                name.startsWith("log-record-snapshot-")
                        || name.startsWith("log-record-export-"));
        List<File> result = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                result.add(f);
            }
        }
        return result;
    }

    private String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private int countLines(File file) throws IOException {
        String content = readFile(file);
        if (content.isEmpty()) {
            return 0;
        }
        return content.split("\n").length;
    }

    private void writeString(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
