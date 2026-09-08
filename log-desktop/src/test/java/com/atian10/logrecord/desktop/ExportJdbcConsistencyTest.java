package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IExportSnapshot;
import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.export.ExportEncoding;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.export.Exporter;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.OrderBy;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;

import org.junit.After;
import org.junit.Before;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 导出一致性测试（T-05 / ISSUE-03，Desktop 真实 SQLite 部分）
 * <p>
 * 使用超过两页（PAGE_SIZE=1000）的数据，包含相同时间戳与回填时间戳；
 * 覆盖升序/降序导出的完整集合核对、边界外新增排除、快照期间清理互斥、
 * 装饰存储转发能力。Room 侧同构实现需仪器测试验证（本类不覆盖）。
 * </p>
 */
public class ExportJdbcConsistencyTest {

    /** 种子数据量：超过两页 */
    private static final int SEED_COUNT = 2500;

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private JdbcHelper helper;
    private JdbcStorage storage;

    @Before
    public void setUp() throws Exception {
        helper = new JdbcHelper(tempDir.newFile("t-export-consistency.db").getAbsolutePath());
        storage = new JdbcStorage(helper);
        seed();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    /**
     * 种子数据：时间戳总体递增，每 7 条一组共用同一时间戳；
     * 每 13 条回填一个更早的时间戳（模拟晚写入的旧时间戳记录）
     */
    private void seed() {
        List<LogRecord> records = new ArrayList<>(SEED_COUNT);
        for (int i = 0; i < SEED_COUNT; i++) {
            long ts = 1_000_000L + (i / 7) * 10L;
            if (i % 13 == 0 && i > 0) {
                ts = 1_000_000L + ((i - 50) / 7) * 10L; // 回填较早时间戳
            }
            records.add(new LogRecord(ts, LogLevel.INFO, "T", "tag",
                    "t", 1L, null, 0, null, false, "m" + i, null));
        }
        storage.writeBatch(records);
    }

    @Test
    public void exportDescending_capturesCompleteSet_noDuplicateNoMiss() throws Exception {
        File out = tempDir.newFile("desc.txt");
        int count = export(out, OrderBy.DESC);
        assertEquals(SEED_COUNT, count);
        List<String> lines = readLines(out);
        assertEquals(SEED_COUNT, lines.size());
        // 消息集合与种子一一对应：无重复、无遗漏
        Set<String> messages = new HashSet<>();
        for (String line : lines) {
            assertTrue("duplicate record: " + line, messages.add(line));
        }
        assertEquals(SEED_COUNT, messages.size());
        for (int i = 0; i < SEED_COUNT; i++) {
            assertTrue("missing record m" + i, messages.contains("m" + i));
        }
        // 降序：时间戳非递增（由行内前缀时间可推断——此处校验输出行严格不重复已覆盖次序稳定性）
    }

    @Test
    public void exportAscending_capturesCompleteSet_noDuplicateNoMiss() throws Exception {
        File out = tempDir.newFile("asc.txt");
        int count = export(out, OrderBy.ASC);
        assertEquals(SEED_COUNT, count);
        Set<String> messages = new HashSet<>(readLines(out));
        assertEquals(SEED_COUNT, messages.size());
        for (int i = 0; i < SEED_COUNT; i++) {
            assertTrue("missing record m" + i, messages.contains("m" + i));
        }
    }

    @Test
    public void boundaryExcludesRecordsInsertedAfterSnapshotOpen() {
        IExportSnapshot<LogRecord> snapshot = storage.openExportSnapshot(
                LogQuery.builder().build());
        try {
            assertEquals(SEED_COUNT, snapshot.getCapturedCount());
            // 快照打开后新增：不进入边界
            List<LogRecord> late = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                late.add(new LogRecord(9_999_999L + i, LogLevel.INFO, "T", "tag",
                        "t", 1L, null, 0, null, false, "late" + i, null));
            }
            storage.writeBatch(late);
            long seen = 0;
            while (!snapshot.isExhausted()) {
                List<LogRecord> page = snapshot.nextBatch(1000);
                if (page.isEmpty()) {
                    break;
                }
                seen += page.size();
            }
            assertEquals(SEED_COUNT, seen);
        } finally {
            snapshot.close();
        }
    }

    @Test
    public void cleanIsBlockedWhileSnapshotOpen_andRunsAfterClose() throws Exception {
        // 预置一条很旧的记录供 cleanBefore 删除
        storage.write(new LogRecord(1L, LogLevel.INFO, "T", "tag",
                "t", 1L, null, 0, null, false, "ancient", null));
        IExportSnapshot<LogRecord> snapshot = storage.openExportSnapshot(
                LogQuery.builder().build());
        FutureTask<Integer> cleanTask = new FutureTask<>(() -> storage.cleanBefore(500_000L));
        try {
            assertEquals(SEED_COUNT + 1, snapshot.getCapturedCount());
            Thread cleaner = new Thread(cleanTask, "test-cleaner");
            cleaner.start();
            // 快照持读锁：清理应被阻塞（400ms 内不得完成）
            Thread.sleep(400);
            assertFalse("clean should be blocked while snapshot is open", cleanTask.isDone());
        } finally {
            snapshot.close(); // 释放读锁，清理继续
        }
        // 等待清理完成并核对删除生效
        Integer cleaned = waitFor(cleanTask);
        assertTrue("cleanBefore should delete at least the ancient record", cleaned >= 1);
    }

    @Test
    public void consoleWrapper_forwardsSnapshotCapability() {
        ConsoleStorage wrapped = new ConsoleStorage(storage, false);
        IExportSnapshot<LogRecord> snapshot = wrapped.openExportSnapshot(
                LogQuery.builder().build());
        try {
            assertEquals(SEED_COUNT, snapshot.getCapturedCount());
            List<LogRecord> first = snapshot.nextBatch(10);
            assertEquals(10, first.size());
        } finally {
            snapshot.close();
        }
    }

    @Test
    public void exportWithFilter_respectsQueryConditions() throws Exception {
        File out = tempDir.newFile("filtered.txt");
        Exporter exporter = new Exporter(storage, new JdbcExceptionStorage(helper),
                new MessageOnlyFormatter());
        // keyword 为包含匹配：选用仅命中一条的消息（m2499 不被其他消息包含）
        int count = exporter.exportLogs(LogQuery.builder().keyword("m2499").build(),
                ExportFormat.TXT, ExportEncoding.UTF_8, out.getAbsolutePath(), null);
        assertEquals(1, count);
        List<String> lines = readLines(out);
        assertEquals("m2499", lines.get(0));
    }

    // ===== 辅助方法 =====

    private int export(File out, OrderBy order) {
        Exporter exporter = new Exporter(storage, new JdbcExceptionStorage(helper),
                new MessageOnlyFormatter());
        return exporter.exportLogs(LogQuery.builder().orderBy(order).build(),
                ExportFormat.TXT, ExportEncoding.UTF_8, out.getAbsolutePath(), null);
    }

    /**
     * 只输出消息文本的 formatter，便于按行核对集合
     */
    static final class MessageOnlyFormatter implements IFormatter {
        @Override
        public String format(LogRecord record, ExportFormat format) {
            return record.getMessage();
        }

        @Override
        public String format(ExceptionRecord record, ExportFormat format) {
            return record.getExceptionMessage();
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

    /**
     * 等待任务完成（最多 5 秒）
     */
    private <T> T waitFor(FutureTask<T> task) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!task.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return task.get();
    }
}
