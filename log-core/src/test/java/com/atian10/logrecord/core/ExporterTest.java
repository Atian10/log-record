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

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exporter 测试
 * <p>
 * 覆盖：TXT/JSON/CSV 三种格式导出、异常导出、回调通知、文件内容正确性。
 * </p>
 */
public class ExporterTest {

    @Test
    public void exportLogs_txt_writesAllRecords() throws Exception {
        FakeStorage storage = newStorageWithLogs(5);
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("export.txt");

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);

        assertEquals(5, count);
        List<String> lines = readLines(file);
        assertEquals(5, lines.size());
        // 默认 DESC 且种子时间戳相同：(timestamp, 到达序) 稳定降序 → 首行为最后写入的 msg4
        assertTrue("first line should be latest record, got: " + lines.get(0),
                lines.get(0).contains("msg4"));
    }

    @Test
    public void exportLogs_json_producesValidArray() throws Exception {
        FakeStorage storage = newStorageWithLogs(3);
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("export.json");

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.JSON, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);

        assertEquals(3, count);
        String content = readFile(file);
        // 验证 JSON 数组结构
        assertTrue(content.startsWith("["));
        assertTrue(content.trim().endsWith("]"));
        // 每条记录之间应有逗号分隔（不是行首就有）
        long commaCount = content.chars().filter(c -> c == ',').count();
        assertTrue("JSON array should have commas between records", commaCount >= 2);
    }

    @Test
    public void exportLogs_csv_includesHeaderAndRows() throws Exception {
        FakeStorage storage = newStorageWithLogs(2);
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("export.csv");

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.CSV, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);

        assertEquals(2, count);
        List<String> lines = readLines(file);
        // 第 1 行是表头，后续是数据
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("timestamp"));
        assertTrue(lines.get(0).contains("level"));
        assertTrue(lines.get(0).contains("message"));
    }

    @Test
    public void exportLogs_emptyStorage_writesJsonArray() throws Exception {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("empty.json");

        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.JSON, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);

        assertEquals(0, count);
        String content = readFile(file).trim();
        // 空数组也应有 []
        assertTrue(content.startsWith("["));
        assertTrue(content.endsWith("]"));
    }

    @Test
    public void exportLogs_callbackReceivesSuccess() throws Exception {
        FakeStorage storage = newStorageWithLogs(2);
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("callback.json");

        final AtomicReference<String> successPath = new AtomicReference<>();
        final AtomicInteger successCount = new AtomicInteger(-1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.JSON, ExportEncoding.UTF_8,
                file.getAbsolutePath(), new ExportCallback() {
                    @Override
                    public void onProgress(int exported, int total) {
                    }
                    @Override
                    public void onSuccess(String filePath, int totalCount) {
                        successPath.set(filePath);
                        successCount.set(totalCount);
                    }
                    @Override
                    public void onFailure(Throwable error, int exportedCount) {
                        failure.set(error);
                    }
                });

        assertNotNull("onSuccess should be called", successPath.get());
        assertEquals(2, successCount.get());
        assertEquals(file.getAbsolutePath(), successPath.get());
        assertEquals(null, failure.get());
    }

    @Test
    public void exportExceptions_json_writesAllExceptions() throws Exception {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        // 准备 3 条异常
        for (int i = 0; i < 3; i++) {
            exStorage.write(new ExceptionRecord(i, "java.lang.RuntimeException",
                    "err" + i, "stack" + i, "tag" + i));
        }

        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("exceptions.json");

        int count = exporter.exportExceptions(
                com.atian10.logrecord.core.query.ExceptionQuery.builder().build(),
                ExportFormat.JSON, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);

        assertEquals(3, count);
        String content = readFile(file);
        assertTrue(content.startsWith("["));
        assertTrue(content.trim().endsWith("]"));
    }

    @Test
    public void exportLogs_nullFormatter_usesDefault() throws Exception {
        // 传 null formatter 应自动用 DefaultFormatter，不抛异常
        FakeStorage storage = newStorageWithLogs(1);
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, null);
        File file = tempFile("default.txt");
        int count = exporter.exportLogs(LogQuery.builder().build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);
        assertEquals(1, count);
    }

    @Test
    public void exportLogs_nullQuery_usesDefaultQuery() throws Exception {
        FakeStorage storage = newStorageWithLogs(1);
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("null-query.txt");
        int count = exporter.exportLogs(null,
                ExportFormat.TXT, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);
        assertEquals(1, count);
    }

    @Test
    public void exportLogs_withFilter_onlyExportsMatching() throws Exception {
        FakeStorage storage = new FakeStorage();
        for (int i = 0; i < 10; i++) {
            LogLevel level = i < 3 ? LogLevel.ERROR : LogLevel.INFO;
            storage.write(new LogRecord(i, level, "T", "tag",
                    "main", 1L, null, 0, null, false, "msg" + i, null));
        }
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        Exporter exporter = new Exporter(storage, exStorage, new DefaultFormatter());
        File file = tempFile("filtered.txt");

        int count = exporter.exportLogs(LogQuery.builder().level(LogLevel.ERROR).build(),
                ExportFormat.TXT, ExportEncoding.UTF_8,
                file.getAbsolutePath(), null);

        assertEquals(3, count);
    }

    // ===== 辅助方法 =====

    private FakeStorage newStorageWithLogs(int n) {
        FakeStorage storage = new FakeStorage();
        for (int i = 0; i < n; i++) {
            Map<String, String> fields = new HashMap<>();
            fields.put("k", "v" + i);
            storage.write(new LogRecord(i, LogLevel.INFO, "T", "tag",
                    "main", 1L, "m", 1, "v1", false, "msg" + i, fields));
        }
        return storage;
    }

    private File tempFile(String suffix) throws IOException {
        File f = File.createTempFile("log-record-test-", "-" + suffix);
        f.deleteOnExit();
        return f;
    }

    private String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
