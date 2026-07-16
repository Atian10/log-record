package com.atian10.logrecord.core;

import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.model.StandardLogType;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * LogRecord 数据模型测试
 * <p>
 * 覆盖：构造、字段访问、不可变性、equals/hashCode、userFields 不可变视图。
 * </p>
 */
public class LogRecordTest {

    @Test
    public void constructor_assignsAllFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("userId", "10086");

        LogRecord r = new LogRecord(
                1700000000L,
                LogLevel.WARN,
                StandardLogType.NETWORK.code(),
                "TagA",
                "main",
                1L,
                "methodX",
                42,
                "v1.0",
                true,
                "hello",
                fields
        );

        assertEquals(1700000000L, r.getTimestamp());
        assertEquals(LogLevel.WARN, r.getLevel());
        assertEquals(StandardLogType.NETWORK.code(), r.getType());
        assertEquals("TagA", r.getTag());
        assertEquals("main", r.getThreadName());
        assertEquals(1L, r.getThreadId());
        assertEquals("methodX", r.getMethodName());
        assertEquals(42, r.getLineNumber());
        assertEquals("v1.0", r.getVersionTag());
        assertTrue(r.isHasException());
        assertEquals("hello", r.getMessage());
        assertEquals("10086", r.getUserFields().get("userId"));
    }

    @Test
    public void userFields_isUnmodifiable() {
        Map<String, String> fields = new HashMap<>();
        fields.put("k1", "v1");
        LogRecord r = new LogRecord(1L, LogLevel.INFO, "T", "tag",
                "main", 1L, null, 0, null, false, "msg", fields);

        try {
            r.getUserFields().put("k2", "v2");
            fail("userFields should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // 期望异常
        }
    }

    @Test
    public void userFields_null_returnsNull() {
        LogRecord r = new LogRecord(1L, LogLevel.INFO, "T", "tag",
                "main", 1L, null, 0, null, false, "msg", null);
        assertNull(r.getUserFields());
    }

    @Test
    public void equals_sameContent_returnsTrue() {
        Map<String, String> f1 = new HashMap<>();
        f1.put("k", "v");
        Map<String, String> f2 = new HashMap<>();
        f2.put("k", "v");

        LogRecord a = new LogRecord(100L, LogLevel.ERROR, "NET", "tag",
                "t", 2L, "m", 5, "v1", true, "msg", f1);
        LogRecord b = new LogRecord(100L, LogLevel.ERROR, "NET", "tag",
                "t", 2L, "m", 5, "v1", true, "msg", f2);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_differentTimestamp_returnsFalse() {
        LogRecord a = new LogRecord(100L, LogLevel.ERROR, "NET", "tag",
                "t", 2L, "m", 5, "v1", true, "msg", null);
        LogRecord b = new LogRecord(101L, LogLevel.ERROR, "NET", "tag",
                "t", 2L, "m", 5, "v1", true, "msg", null);

        assertNotEquals(a, b);
    }

    @Test
    public void equals_differentUserFields_returnsFalse() {
        Map<String, String> f1 = new HashMap<>();
        f1.put("k", "v1");
        Map<String, String> f2 = new HashMap<>();
        f2.put("k", "v2");

        LogRecord a = new LogRecord(100L, LogLevel.ERROR, "NET", "tag",
                "t", 2L, "m", 5, "v1", true, "msg", f1);
        LogRecord b = new LogRecord(100L, LogLevel.ERROR, "NET", "tag",
                "t", 2L, "m", 5, "v1", true, "msg", f2);

        assertNotEquals(a, b);
    }

    @Test
    public void equals_null_returnsFalse() {
        LogRecord a = new LogRecord(1L, LogLevel.INFO, "T", "tag",
                "main", 1L, null, 0, null, false, "msg", null);
        assertFalse(a.equals(null));
    }

    @Test
    public void equals_self_returnsTrue() {
        LogRecord a = new LogRecord(1L, LogLevel.INFO, "T", "tag",
                "main", 1L, null, 0, null, false, "msg", null);
        assertTrue(a.equals(a));
    }

    @Test
    public void toString_containsKeyFields() {
        LogRecord r = new LogRecord(100L, LogLevel.ERROR, "NET", "LoginTag",
                "main", 1L, "doLogin", 42, "v1.2", true, "fail", null);
        String s = r.toString();
        assertTrue(s.contains("timestamp=100"));
        assertTrue(s.contains("ERROR"));
        assertTrue(s.contains("LoginTag"));
        assertTrue(s.contains("doLogin"));
        assertTrue(s.contains("fail"));
    }
}
