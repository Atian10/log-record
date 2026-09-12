package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IExportSnapshot;
import com.atian10.logrecord.core.clean.CapacityBudget;
import com.atian10.logrecord.core.clean.CleanResult;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 桌面端全库容量协调测试（T-07 / ISSUE-05，真实 SQLite 子集）
 * <p>
 * 覆盖：保留期内超限按最旧删除至达标、受保护表不参与、无可删数据未达标、
 * 导出快照复制期间推迟、无预算时表级数量清理仍生效。
 * 补充源码覆盖：INCREMENTAL 无空闲页、checkpoint 忙碌、回收失败跨轮及并列排序。
 * 当前修改未执行；磁盘不足、不同设备与文件系统组合仍缺运行证据。
 * </p>
 */
public class JdbcCapacityCoordinatorTest {

    /** 生成大消息的目标总量（800 行 × 8KB ≈ 6.4MB，超过单批上限便于断言部分保留） */
    private static final int BIG_ROWS = 800;
    private static final int MESSAGE_KB = 8;

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private JdbcHelper helper;
    private JdbcStorage storage;
    private JdbcExceptionStorage exceptionStorage;
    private JdbcCapacityCoordinator coordinator;

    @Before
    public void setUp() throws Exception {
        helper = new JdbcHelper(tempDir.newFile("t-capacity.db").getAbsolutePath());
        // 开启 FULL auto_vacuum 使删除后文件可自动收缩（对既有空库需 VACUUM 生效）
        helper.executeUpdate("PRAGMA auto_vacuum=1", null);
        helper.executeUpdate("VACUUM", null);
        storage = new JdbcStorage(helper);
        exceptionStorage = new JdbcExceptionStorage(helper);
        coordinator = new JdbcCapacityCoordinator(helper, storage, exceptionStorage);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void overLimit_deletesOldestAcrossTables_untilMet() {
        seedBigLogs(BIG_ROWS, MESSAGE_KB, 1_000_000L);
        seedExceptions(20, 900_000L);
        long total = helper.getDbTotalSizeBytes();
        assertTrue("seeded database should exceed 3.5MB, was " + total, total > 3500L * 1024L);

        // 预算 3.5MB：两表均参与，删除最旧记录直至达标（首批上限 500 条）
        CleanResult result = coordinator.enforceCapacity(
                new CapacityBudget(3500L * 1024L, true, true));

        assertEquals("expected MET, got " + result, CleanResult.Outcome.MET, result.getOutcome());
        assertTrue(result.getBytesAfter() <= result.getTargetBytes());
        assertTrue("should have deleted old records", result.getDeletedLogs() > 0);
        assertEquals("older exceptions should be deleted first batch",
                20, result.getDeletedExceptions());
        // 删除的是最旧的：应保留较新的部分日志（数据量超过单批上限）
        assertTrue("some newest logs should be retained, remaining="
                        + storage.getRecordCount(),
                storage.getRecordCount() > 0);
        long minRemaining = minTimestamp("log_record");
        assertTrue("oldest records should be deleted first, min remaining=" + minRemaining,
                minRemaining >= 1_000_000L + 400L);
        assertEquals(BIG_ROWS - result.getDeletedLogs(), storage.getRecordCount());
    }

    @Test
    public void protectedTable_notDeleted_whenNotEligible() {
        seedBigLogs(BIG_ROWS, MESSAGE_KB, 1_000_000L);
        seedExceptions(20, 900_000L);

        // 仅日志表参与：异常表受保护，即使其记录更旧
        CleanResult result = coordinator.enforceCapacity(
                new CapacityBudget(1024L * 1024L, true, false));

        assertEquals(CleanResult.Outcome.MET, result.getOutcome());
        assertEquals("protected exception table must keep all rows",
                20L, exceptionStorage.getRecordCount());
        assertEquals(0, result.getDeletedExceptions());
        assertTrue(result.getDeletedLogs() > 0);
    }

    @Test
    public void noDeletableData_reportsNotMet() {
        // 空表 + 极小预算：无法达标且无可删数据
        CleanResult result = coordinator.enforceCapacity(
                new CapacityBudget(1024L, true, true));
        assertEquals(CleanResult.Outcome.NOT_MET, result.getOutcome());
        assertNotNull(result.getStopReason());
        assertTrue(result.getStopReason(), result.getStopReason().contains("no deletable"));
    }

    @Test
    public void snapshotCopying_postponesMaintenance() {
        seedBigLogs(BIG_ROWS, MESSAGE_KB, 1_000_000L);
        IExportSnapshot<LogRecord> snapshot = storage.openExportSnapshot(
                LogQuery.builder().build());
        try {
            CleanResult result = coordinator.enforceCapacity(
                    new CapacityBudget(1024L * 1024L, true, true));
            assertEquals(CleanResult.Outcome.POSTPONED, result.getOutcome());
            assertEquals(BIG_ROWS, storage.getRecordCount());
        } finally {
            snapshot.close();
        }
        // 快照关闭后可正常执行
        CleanResult after = coordinator.enforceCapacity(
                new CapacityBudget(1024L * 1024L, true, true));
        assertEquals(CleanResult.Outcome.MET, after.getOutcome());
    }

    @Test
    public void noBudget_tableLevelCountCleanStillWorks() {
        // 表级规则与容量解耦：无容量预算时 maxRecordCount 仍生效
        seedBigLogs(30, 1, 1_000_000L);
        CleanPolicy policy = CleanPolicy.builder()
                .enable(true).maxRecordCount(10).build();
        int cleaned = storage.clean(policy);
        assertTrue("count clean should delete rows", cleaned >= 20);
        assertEquals(10L, storage.getRecordCount());
    }

    /** INCREMENTAL 初始无空闲页时仍能删除一批并回收，不能空转到工作上限。 */
    @Test public void incrementalWithoutFreePagesMakesProgress() throws Exception {
        helper.executeUpdate("PRAGMA auto_vacuum=2", null);
        helper.executeUpdate("VACUUM", null);
        seedBigLogs(BIG_ROWS, MESSAGE_KB, 1L);
        assertEquals(0L, helper.queryLong("PRAGMA freelist_count", null));
        CleanResult result = coordinator.enforceCapacity(new CapacityBudget(3500L * 1024L, true, true));
        assertEquals(CleanResult.Outcome.MET, result.getOutcome());
        assertTrue(result.getDeletedLogs() > 0);
    }

    /** 相同时间戳也按全库顺序：更旧异常必须包含在第一批的 500 条中。 */
    @Test public void timestampTiesDoNotLetLogsSkipOlderExceptions() throws Exception {
        seedBigLogs(BIG_ROWS, MESSAGE_KB, 2L);
        helper.executeUpdate("UPDATE log_record SET timestamp=2", null);
        seedExceptions(20, 1L);
        helper.executeUpdate("UPDATE exception_table SET timestamp=1", null);
        CleanResult result = coordinator.enforceCapacity(new CapacityBudget(3500L * 1024L, true, true));
        assertEquals(CleanResult.Outcome.MET, result.getOutcome());
        assertEquals(20, result.getDeletedExceptions());
        assertEquals(480, result.getDeletedLogs());
        assertEquals(320L, storage.getRecordCount());
    }

    /** busy 连续轮次都不得删除；解除 busy 的恢复轮次仍必须保持零删除。 */
    @Test public void checkpointBusyBlocksDeletesAcrossRounds() throws Exception {
        seedBigLogs(BIG_ROWS, MESSAGE_KB, 1L);
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        injectMaintenanceFailure(fail, true);
        CapacityBudget budget = new CapacityBudget(3500L * 1024L, true, true);
        for (int round = 0; round < 2; round++) {
            CleanResult result = coordinator.enforceCapacity(budget);
            assertEquals(CleanResult.Outcome.POSTPONED, result.getOutcome());
            assertEquals(0, result.getDeletedLogs());
            assertEquals(BIG_ROWS, storage.getRecordCount());
        }
        fail.set(false);
        CleanResult recovery = coordinator.enforceCapacity(budget);
        assertEquals(CleanResult.Outcome.NOT_MET, recovery.getOutcome());
        assertEquals(0, recovery.getDeletedLogs());
        assertEquals(BIG_ROWS, storage.getRecordCount());
        assertEquals(CleanResult.Outcome.MET, coordinator.enforceCapacity(budget).getOutcome());
    }

    /** 删除后回收失败只能冻结后续删除；持续失败的第二轮不能再删一批。 */
    @Test public void reclaimFailureFreezesFollowingRounds() throws Exception {
        helper.executeUpdate("PRAGMA auto_vacuum=2", null);
        helper.executeUpdate("VACUUM", null);
        seedBigLogs(BIG_ROWS, MESSAGE_KB, 1L);
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        injectMaintenanceFailure(fail, false);
        CapacityBudget budget = new CapacityBudget(3500L * 1024L, true, true);
        CleanResult first = coordinator.enforceCapacity(budget);
        assertEquals(CleanResult.Outcome.FAILED, first.getOutcome());
        assertEquals(500, first.getDeletedLogs());
        assertEquals(-1L, first.getBytesAfter());
        long remaining = storage.getRecordCount();
        CleanResult second = coordinator.enforceCapacity(budget);
        assertEquals(CleanResult.Outcome.FAILED, second.getOutcome());
        assertEquals(0, second.getDeletedLogs());
        assertEquals(remaining, storage.getRecordCount());
        fail.set(false);
        CleanResult recovery = coordinator.enforceCapacity(budget);
        assertEquals(CleanResult.Outcome.MET, recovery.getOutcome());
        assertEquals(0, recovery.getDeletedLogs());
    }

    /** 测试专用连接代理：busy 返回三列结果，回收故障在 SQL 准备阶段抛出。 */
    private void injectMaintenanceFailure(java.util.concurrent.atomic.AtomicBoolean fail, boolean busy) throws Exception {
        java.sql.Connection original = helper.getConnection();
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (ignored, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && fail.get()) {
                        String sql = (String) args[0];
                        if (busy && sql.contains("wal_checkpoint"))
                            return original.prepareStatement("SELECT 1, 0, 0");
                        if (!busy && sql.contains("incremental_vacuum"))
                            throw new SQLException("injected reclaim failure");
                    }
                    try {
                        Object value = method.invoke(original, args);
                        if ("createStatement".equals(method.getName())) {
                            java.sql.Statement statement = (java.sql.Statement) value;
                            return java.lang.reflect.Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(),
                                    new Class<?>[]{java.sql.Statement.class}, (target, operation, parameters) -> {
                                        if (!busy && fail.get() && "executeUpdate".equals(operation.getName())
                                                && ((String) parameters[0]).contains("incremental_vacuum"))
                                            throw new SQLException("injected reclaim failure");
                                        try { return operation.invoke(statement, parameters); }
                                        catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                                    });
                        }
                        return value;
                    }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
        java.lang.reflect.Field field = JdbcHelper.class.getDeclaredField("connection");
        field.setAccessible(true);
        field.set(helper, proxy);
    }

    // ===== 辅助方法 =====

    /**
     * 写入大消息日志：count 行 × kb KB 消息，时间戳自 base 递增
     */
    private void seedBigLogs(int count, int kb, long baseTimestamp) {
        StringBuilder sb = new StringBuilder(kb * 1024);
        for (int i = 0; i < kb * 1024; i++) {
            sb.append('x');
        }
        String message = sb.toString();
        List<LogRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            records.add(new LogRecord(baseTimestamp + i, LogLevel.INFO, "T", "tag",
                    "t", 1L, null, 0, null, false, message, null));
        }
        storage.writeBatch(records);
    }

    /**
     * 写入小异常记录：时间戳自 base 递增
     */
    private void seedExceptions(int count, long baseTimestamp) {
        List<com.atian10.logrecord.core.model.ExceptionRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            records.add(new com.atian10.logrecord.core.model.ExceptionRecord(
                    baseTimestamp + i, "java.lang.RuntimeException", "cap-" + i, "stack", "tag"));
        }
        exceptionStorage.writeBatch(records);
    }

    /**
     * 查询表的最小时间戳
     */
    private long minTimestamp(String table) {
        try {
            return helper.queryLong("SELECT MIN(timestamp) FROM " + table, null);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
