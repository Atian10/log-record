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
 * 连接关闭（提交结果不确定路径）时禁止自动重放，整体按失败上报。
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

    @Test
    public void write_withClosedHelper_propagatesFailure() throws Exception {
        helper.close();
        try {
            storage.write(logRecord("after-close"));
            fail("expected BatchWriteException for closed helper");
        } catch (BatchWriteException e) {
            assertEquals(1, e.getAttempted());
            assertEquals(0, e.getSaved());
        }
        // 兼容入口保持静默（由引擎容错），不再单独断言
    }

    @Test
    public void writeBatch_withClosedHelper_reportsUncertainWithoutReplay() throws Exception {
        helper.close();
        List<LogRecord> records = new ArrayList<>();
        records.add(logRecord("u1"));
        records.add(logRecord("u2"));
        records.add(logRecord("u3"));
        try {
            storage.writeBatch(records);
            fail("expected BatchWriteException for closed helper");
        } catch (BatchWriteException e) {
            // 提交结果不确定（含连接不可用）：整批失败、saved=0，禁止自动重放
            assertEquals(3, e.getAttempted());
            assertEquals(0, e.getSaved());
            assertTrue(e.getMessage(), e.getMessage().contains("uncertain"));
        }
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
