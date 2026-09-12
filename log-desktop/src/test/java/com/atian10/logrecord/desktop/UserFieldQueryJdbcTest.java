package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.desktop.jdbc.JdbcHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * userFields 查询条件测试（T-03 / ISSUE-07，Desktop 真实 SQLite 部分）
 * <p>
 * 写入包含引号、反斜杠、换行、百分号、下划线、大小写差异、空字符串、null 值与
 * 多字段组合的记录，验证 query、count、statistics 使用同一 JSON 编码条件，
 * 大小写敏感且无误匹配。Room 侧同构实现需仪器测试验证（本类不覆盖）。
 * </p>
 */
public class UserFieldQueryJdbcTest {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private JdbcHelper helper;
    private JdbcStorage storage;

    @Before
    public void setUp() throws Exception {
        helper = new JdbcHelper(tempDir.newFile("t-user-fields.db").getAbsolutePath());
        storage = new JdbcStorage(helper);
        writeFixtures();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    /**
     * 写入固定数据集：每条记录一个独立场景
     */
    private void writeFixtures() {
        List<LogRecord> records = new ArrayList<>();
        records.add(record("plain", "value", null));
        records.add(record("tricky", "a\"b\\c", null));
        records.add(record("multi", "line1\nline2", null));
        records.add(record("wild", "100%_x", null));
        records.add(record("case", "Value", null));
        records.add(record("empty", "", null));
        records.add(record("nul", null, null));
        Map<String, String> two = new HashMap<>();
        two.put("k1", "v1");
        two.put("k2", "v2");
        records.add(record(null, null, two));
        records.add(record(null, null, null));
        storage.writeBatch(records);
    }

    /** 键内的转义引号不能让键后缀伪装成独立 JSON 成员。 */
    @Test public void escapedKeySuffixDoesNotMatchBareMember() {
        storage.write(record("prefix\\\"key", "value", null));
        storage.write(record("prefix,\\\"key", "value", null));
        assertUserFieldCount(0, "key", "value");
        LogQuery negative = LogQuery.builder().userField("key", "value").build();
        assertEquals(0L, storage.statistics(negative).getTotalCount());
        try (com.atian10.logrecord.core.IExportSnapshot<LogRecord> snapshot = storage.openExportSnapshot(negative)) {
            assertEquals(0L, snapshot.getCapturedCount());
            assertEquals(0, snapshot.nextBatch(10).size());
        }
        assertUserFieldCount(1, "prefix\\\"key", "value");
    }

    @Test
    public void query_plainValue_matchesExactlyOneRow() {
        assertUserFieldCount(1, "plain", "value");
        assertUserFieldCount(0, "plain", "valu");   // 无前缀误匹配
        assertUserFieldCount(0, "pla", "value");     // 无键前缀误匹配
    }

    @Test
    public void query_quoteAndBackslash_matchesJsonEncodedRow() {
        // 原始 LIKE 转义在该场景查不到（ISSUE-07 触发条件），instr 按 JSON 编码匹配
        assertUserFieldCount(1, "tricky", "a\"b\\c");
        assertUserFieldCount(0, "tricky", "a\"b\\C");
    }

    @Test
    public void query_newline_matchesJsonEncodedRow() {
        assertUserFieldCount(1, "multi", "line1\nline2");
        assertUserFieldCount(0, "multi", "line1 line2");
    }

    @Test
    public void query_percentUnderscore_treatedLiterally() {
        // % 与 _ 是 LIKE 通配符；instr 视为普通字符
        assertUserFieldCount(1, "wild", "100%_x");
        assertUserFieldCount(0, "wild", "100%_X");
        assertUserFieldCount(0, "wild", "100__x");
    }

    @Test
    public void query_caseSensitive_noAsciiCaseFolding() {
        assertUserFieldCount(1, "case", "Value");
        assertUserFieldCount(0, "case", "value");   // LIKE 默认对 ASCII 不区分大小写，此处必须区分
        assertUserFieldCount(0, "Case", "Value");
    }

    @Test
    public void query_emptyString_matchesOnlyEmpty() {
        assertUserFieldCount(1, "empty", "");
        assertUserFieldCount(0, "nul", "");
    }

    @Test
    public void query_nullValue_matchesNullLiteral() {
        assertUserFieldCount(1, "nul", null);
        assertUserFieldCount(0, "nul", "null");     // 字符串 "null" 不得误匹配 null 值行
    }

    @Test
    public void query_multiField_allConditionsMustMatch() {
        LogQuery.Builder both = LogQuery.builder().userField("k1", "v1").userField("k2", "v2");
        assertEquals(1L, storage.count(both.build()));
        assertEquals(1, storage.query(both.build()).size());
        // 仅一个条件也命中（多字段行包含该成员）
        assertUserFieldCount(1, "k1", "v1");
        assertUserFieldCount(0, "k1", "v2");
    }

    @Test
    public void countQueryStatistics_useSameConditionSemantics() {
        // 三类入口共用同一条件构造：数量一致
        LogQuery query = LogQuery.builder().userField("k1", "v1").build();
        long byCount = storage.count(query);
        int byQuery = storage.query(query).size();
        LogStatistics stats = storage.statistics(query);
        assertEquals(byCount, byQuery);
        assertEquals(byCount, stats.getTotalCount());
    }

    // ===== 辅助方法 =====

    /**
     * 断言按单个键值条件查询的数量
     */
    private void assertUserFieldCount(int expected, String key, String value) {
        LogQuery query = LogQuery.builder().userField(key, value).build();
        assertEquals("count(" + key + "," + value + ")",
                (long) expected, storage.count(query));
        assertEquals("query(" + key + "," + value + ").size()",
                expected, storage.query(query).size());
    }

    /**
     * 构建带 userFields 的最小日志记录；fields 为 null 且 singleKey 为 null 表示无字段
     */
    private LogRecord record(String singleKey, String singleValue, Map<String, String> fields) {
        Map<String, String> userFields = fields;
        if (singleKey != null) {
            userFields = new HashMap<>();
            userFields.put(singleKey, singleValue);
        }
        return new LogRecord(1L, LogLevel.INFO, "T", "tag",
                Thread.currentThread().getName(), Thread.currentThread().getId(),
                null, 0, null, false, "msg", userFields);
    }
}
