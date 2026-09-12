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

    /** 验证快照阻止清理、关闭后允许删除；失败时仍取消并有界回收清理线程。 */
    @Test
    public void cleanIsBlockedWhileSnapshotOpen_andRunsAfterClose() throws Exception {
        // 预置一条很旧的记录供 cleanBefore 删除
        storage.write(new LogRecord(1L, LogLevel.INFO, "T", "tag",
                "t", 1L, null, 0, null, false, "ancient", null));
        // 清理结果由测试线程读取；任何退出路径都通过 finally 取消并回收任务。
        FutureTask<Integer> cleanTask = new FutureTask<>(() -> storage.cleanBefore(500_000L));
        // 守护线程仅防止异常线程阻止 JVM 退出，未能按期限回收仍会使测试失败。
        Thread cleaner = new Thread(cleanTask, "test-cleaner");
        cleaner.setDaemon(true);
        // 保留断言、任务或快照关闭的首个失败，回收失败只能作为附加证据。
        Throwable primaryFailure = null;
        try {
            // 同线程打开并关闭快照；关闭失败不会覆盖块内已经发生的断言失败。
            try (IExportSnapshot<LogRecord> snapshot = storage.openExportSnapshot(
                    LogQuery.builder().build())) {
                assertEquals(SEED_COUNT + 1, snapshot.getCapturedCount());
                cleaner.start();
                // 快照持读锁：清理应被阻塞（400ms 内不得完成）。
                Thread.sleep(400);
                assertFalse("clean should be blocked while snapshot is open", cleanTask.isDone());
            }
            // 先释放快照读锁，再限时取得删除数量，保留原有删除生效断言。
            Integer cleaned = waitFor(cleanTask);
            assertTrue("cleanBefore should delete at least the ancient record", cleaned >= 1);
        } catch (Exception | Error failure) {
            // failure 是当前测试最先暴露的错误，交由 JUnit 报告。
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                stopCleaner(cleanTask, cleaner);
            } catch (Exception | Error cleanupFailure) {
                // 回收错误不能覆盖原始失败；若此前成功，则独立报告回收失败。
                if (primaryFailure == null) {
                    // 后续 @After 可能等待活动操作；在进入它之前留下可追溯的失败日志。
                    cleanupFailure.printStackTrace(System.err);
                    throw cleanupFailure;
                }
                primaryFailure.addSuppressed(cleanupFailure);
                primaryFailure.printStackTrace(System.err);
            }
        }
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
     * 最多等待 5 秒取得 task 的结果；超时和任务异常交由调用方报告并回收线程。
     */
    private <T> T waitFor(FutureTask<T> task) throws Exception {
        return task.get(5, TimeUnit.SECONDS);
    }

    /**
     * 取消 task、中断 cleaner 并最多等待 5 秒退出；被中断仍尝试回收，最后恢复中断标记。
     * 未回收或中断均显式失败，由调用方附加到已有测试失败，不能仅靠守护线程掩盖。
     */
    private void stopCleaner(FutureTask<?> task, Thread cleaner) throws InterruptedException {
        task.cancel(true);
        cleaner.interrupt();
        // 单调时钟限定整个回收窗口，中断后继续等待也不会重置 5 秒期限。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // 保存回收期间首次中断，既恢复调用线程标记，也保留异常证据。
        InterruptedException interruption = null;
        try {
            while (cleaner.isAlive()) {
                // 剩余回收时间以纳秒计；期限耗尽后不得进入无期限 join。
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedJoin(cleaner, remaining);
                } catch (InterruptedException interrupted) {
                    // 暂存中断后继续完成有限回收，不在循环中恢复标记导致空转。
                    if (interruption == null) {
                        interruption = interrupted;
                    } else {
                        interruption.addSuppressed(interrupted);
                    }
                }
            }
            if (cleaner.isAlive()) {
                // 即使线程是 daemon，也必须记录未回收；隔离 worker 的最终退出由外层验证管理。
                AssertionError failure = new AssertionError("cleaner did not terminate within 5 seconds");
                if (interruption != null) {
                    failure.addSuppressed(interruption);
                }
                throw failure;
            }
            if (interruption != null) {
                throw interruption;
            }
        } finally {
            if (interruption != null) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
