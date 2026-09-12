package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.BatchWriteException;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.desktop.jdbc.BatchRolledBackException;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JDBC 写入失败传递测试（T-02 / ISSUE-02）
 * <p>
 * 使用测试专属临时真实 SQLite 数据库，通过触发器注入批量失败：
 * 确认回滚的事务被逐条重放并如实上报保存/失败数量，且不产生重复记录；
 * 关闭后拒绝准入；提交未知不重放；提交后清理失败仍报告 SAVED。
 * </p>
 */
public class JdbcStorageWriteFailureTest {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private JdbcHelper helper;
    private JdbcStorage storage;
    private JdbcExceptionStorage exceptionStorage;

    @Before
    public void setUp() throws Exception {
        helper = new JdbcHelper(tempDir.newFile("t-write-failure.db").getAbsolutePath());
        storage = new JdbcStorage(helper);
        exceptionStorage = new JdbcExceptionStorage(helper);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void writeBatch_rolledBack_replaysPerRecord_andReportsPartialFailure() {
        createTrigger("trg_boom",
                "CREATE TRIGGER trg_boom BEFORE INSERT ON log_record "
                        + "WHEN NEW.message = 'BOOM' "
                        + "BEGIN SELECT RAISE(ABORT, 'boom'); END");

        List<LogRecord> records = new ArrayList<>();
        records.add(logRecord("ok-1"));
        records.add(logRecord("BOOM"));
        records.add(logRecord("ok-2"));

        try {
            storage.writeBatch(records);
            fail("expected BatchWriteException for partial batch failure");
        } catch (BatchWriteException e) {
            // 存储层降级重放后：3 条尝试、2 条保存、1 条失败
            assertEquals(3, e.getAttempted());
            assertEquals(2, e.getSaved());
        }
        // 重放结果入库：无重复、无残留半批数据（原批量事务已整体回滚）
        assertEquals(2L, storage.getRecordCount());
        assertEquals(1L, storage.count(LogQuery.builder().keyword("ok-1").build()));
        assertEquals(1L, storage.count(LogQuery.builder().keyword("ok-2").build()));
        assertEquals(0L, storage.count(LogQuery.builder().keyword("BOOM").build()));
    }

    @Test
    public void helperExecuteBatch_rolledBackCleanly_throwsRollbackException_andKeepsTableEmpty() throws Exception {
        createTrigger("trg_all",
                "CREATE TRIGGER trg_all BEFORE INSERT ON log_record "
                        + "BEGIN SELECT RAISE(ABORT, 'always'); END");
        Object[][] args = {
                {1L, 20, "T", "tag", null, 0L, null, 0, "m1", null, null, 0},
                {2L, 20, "T", "tag", null, 0L, null, 0, "m2", null, null, 0}
        };
        try {
            helper.executeBatch(
                    "INSERT INTO log_record (timestamp, level, type, tag, thread_name, thread_id, "
                            + "method_name, line_number, message, user_fields, version_tag, has_exception) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    args);
            fail("expected BatchRolledBackException for failing batch");
        } catch (BatchRolledBackException expected) {
            // 事务已确认回滚，调用方可安全重放
        }
        // 批量事务整体回滚：表内无数据，说明没有部分提交
        assertEquals(0L, storage.getRecordCount());

        // 移除触发器后同批重放成功，验证回滚路径未破坏连接状态
        dropTrigger("trg_all");
        List<LogRecord> records = new ArrayList<>();
        records.add(logRecord("m1"));
        records.add(logRecord("m2"));
        storage.writeBatch(records);
        assertEquals(2L, storage.getRecordCount());
    }

    /** 关闭后在进入 SQL 之前拒绝，不作为已尝试的写入结果。 */
    @Test public void closedHelperRejectsNewWrites() {
        helper.close();
        try {
            storage.write(logRecord("after-close"));
            fail("closed guard must reject");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("closing")); }
        try {
            storage.writeBatch(java.util.Collections.singletonList(logRecord("after-close")));
            fail("closed guard must reject batch");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("closing")); }
    }

    /** commit 已完成而 statement.close 失败，仍按已保存上报且不再次插入。 */
    @Test public void committedBatchWithCloseFailureIsSavedWithoutReplay() throws Exception {
        injectConnectionFailure("statementClose");
        try {
            storage.writeBatch(java.util.Arrays.asList(logRecord("one"), logRecord("two")));
            fail("cleanup error should remain observable");
        } catch (BatchWriteException error) {
            assertEquals(2, error.getSaved());
            for (BatchWriteException.ItemOutcome item : error.getItemOutcomes())
                assertEquals(BatchWriteException.ItemOutcome.SAVED, item);
        }
        assertEquals(2L, storage.getRecordCount());
    }

    /** commit 返回前抛错即属未知；隔离连接，禁止回滚和恢复自动提交造成误重放。 */
    @Test public void uncertainCommitIsNotRolledBackOrReplayed() throws Exception {
        List<String> calls = injectConnectionFailure("commit");
        try {
            storage.writeBatch(java.util.Arrays.asList(logRecord("one"), logRecord("two")));
            fail("commit failure must be observable");
        } catch (BatchWriteException error) {
            assertEquals(0, error.getSaved());
            for (BatchWriteException.ItemOutcome item : error.getItemOutcomes())
                assertEquals(BatchWriteException.ItemOutcome.UNKNOWN, item);
        }
        assertEquals(1, java.util.Collections.frequency(calls, "commit"));
        assertEquals(0, java.util.Collections.frequency(calls, "rollback"));
        assertEquals(0, java.util.Collections.frequency(calls, "autoCommit:true"));
        assertEquals(1, java.util.Collections.frequency(calls, "close"));
    }

    /** 自动提交恢复失败不抹掉已提交证据，同时连接不得继续复用。 */
    @Test public void committedBatchWithRestoreFailureRemainsSaved() throws Exception {
        List<String> calls = injectConnectionFailure("restore");
        try {
            storage.writeBatch(java.util.Collections.singletonList(logRecord("one")));
            fail("restore failure must be observable");
        } catch (BatchWriteException error) {
            assertEquals(1, error.getSaved());
            assertEquals(BatchWriteException.ItemOutcome.SAVED, error.getItemOutcomes()[0]);
        }
        assertEquals(1, java.util.Collections.frequency(calls, "commit"));
        assertEquals(1, java.util.Collections.frequency(calls, "close"));
    }

    /** 在真实测试连接外包一层故障代理；仅测试源码使用反射注入，不改生产接口。 */
    private List<String> injectConnectionFailure(String phase) throws Exception {
        java.sql.Connection original = helper.getConnection();
        List<String> calls = new ArrayList<>();
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (ignored, method, args) -> {
                    String name = method.getName();
                    if ("commit".equals(name) || "rollback".equals(name) || "close".equals(name)) calls.add(name);
                    if ("setAutoCommit".equals(name)) calls.add("autoCommit:" + args[0]);
                    if ("commit".equals(name) && "commit".equals(phase))
                        throw new SQLException("injected uncertain commit");
                    if ("setAutoCommit".equals(name) && Boolean.TRUE.equals(args[0]) && "restore".equals(phase))
                        throw new SQLException("injected restore failure");
                    try {
                        Object value = method.invoke(original, args);
                        if ("prepareStatement".equals(name) && "statementClose".equals(phase)
                                && ((String) args[0]).startsWith("INSERT")) {
                            java.sql.PreparedStatement statement = (java.sql.PreparedStatement) value;
                            return java.lang.reflect.Proxy.newProxyInstance(
                                    java.sql.PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class}, (target, operation, parameters) -> {
                                        try {
                                            Object result = operation.invoke(statement, parameters);
                                            if ("close".equals(operation.getName())) throw new SQLException("injected statement close");
                                            return result;
                                        } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                                    });
                        }
                        return value;
                    } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
        java.lang.reflect.Field connectionField = JdbcHelper.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(helper, proxy);
        return calls;
    }

    @Test
    public void exceptionStorage_writeBatch_sameContract() {
        // 注意：不直接使用 helper.getConnection() 的 try-with-resources（会关闭共享连接）
        try {
            helper.executeUpdate("CREATE TRIGGER trg_exp_boom BEFORE INSERT ON exception_table "
                    + "WHEN NEW.exception_msg = 'BOOM' "
                    + "BEGIN SELECT RAISE(ABORT, 'boom'); END", null);
        } catch (SQLException e) {
            throw new IllegalStateException("create trigger failed", e);
        }
        List<ExceptionRecord> records = new ArrayList<>();
        records.add(new ExceptionRecord(1L, "java.lang.RuntimeException", "ok-1", "stack", "tag"));
        records.add(new ExceptionRecord(2L, "java.lang.RuntimeException", "BOOM", "stack", "tag"));
        records.add(new ExceptionRecord(3L, "java.lang.RuntimeException", "ok-2", "stack", "tag"));
        try {
            exceptionStorage.writeBatch(records);
            fail("expected BatchWriteException for partial exception batch failure");
        } catch (BatchWriteException e) {
            assertEquals(3, e.getAttempted());
            assertEquals(2, e.getSaved());
        }
        assertEquals(2L, exceptionStorage.getRecordCount());
        assertEquals(1L, exceptionStorage.count(ExceptionQuery.builder().keyword("ok-1").build()));
        assertEquals(0L, exceptionStorage.count(ExceptionQuery.builder().keyword("BOOM").build()));
    }

    // ===== 辅助方法 =====

    /**
     * 在测试数据库上创建触发器
     * <p>经 executeUpdate 执行 DDL，避免触碰共享原始连接</p>
     */
    private void createTrigger(String name, String sql) {
        try {
            helper.executeUpdate(sql, null);
        } catch (SQLException e) {
            throw new IllegalStateException("create trigger " + name + " failed", e);
        }
    }

    /**
     * 删除测试数据库上的触发器
     */
    private void dropTrigger(String name) {
        try {
            helper.executeUpdate("DROP TRIGGER IF EXISTS " + name, null);
        } catch (SQLException e) {
            throw new IllegalStateException("drop trigger " + name + " failed", e);
        }
    }

    /**
     * 构建最小日志记录
     */
    private LogRecord logRecord(String message) {
        return new LogRecord(1L, LogLevel.INFO, "T", "tag",
                Thread.currentThread().getName(), Thread.currentThread().getId(),
                null, 0, null, false, message, null);
    }
}
