package com.atian10.logrecord.core;

import com.atian10.logrecord.core.util.JsonUtil;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JsonUtil 测试
 * <p>
 * 覆盖：序列化、反序列化、空值处理、转义、往返一致性。
 * </p>
 */
public class JsonUtilTest {

    @Test
    public void mapToJson_normal_returnsJsonString() {
        Map<String, String> map = new HashMap<>();
        map.put("k1", "v1");
        map.put("k2", "v2");
        String json = JsonUtil.mapToJson(map);
        // Gson 不保证 key 顺序，只验证包含
        assertTrue(json.contains("\"k1\":\"v1\""));
        assertTrue(json.contains("\"k2\":\"v2\""));
    }

    @Test
    public void mapToJson_null_returnsBraces() {
        assertEquals("{}", JsonUtil.mapToJson(null));
    }

    @Test
    public void mapToJson_empty_returnsBraces() {
        assertEquals("{}", JsonUtil.mapToJson(new HashMap<String, String>()));
    }

    @Test
    public void jsonToMap_normal_returnsMap() {
        String json = "{\"k1\":\"v1\",\"k2\":\"v2\"}";
        Map<String, String> map = JsonUtil.jsonToMap(json);
        assertEquals("v1", map.get("k1"));
        assertEquals("v2", map.get("k2"));
    }

    @Test
    public void jsonToMap_null_returnsEmpty() {
        assertTrue(JsonUtil.jsonToMap(null).isEmpty());
    }

    @Test
    public void jsonToMap_empty_returnsEmpty() {
        assertTrue(JsonUtil.jsonToMap("").isEmpty());
    }

    @Test
    public void jsonToMap_bracesOnly_returnsEmpty() {
        assertTrue(JsonUtil.jsonToMap("{}").isEmpty());
    }

    @Test
    public void jsonToMap_invalidJson_returnsEmpty() {
        assertTrue(JsonUtil.jsonToMap("not-a-json").isEmpty());
    }

    @Test
    public void roundTrip_preservesContent() {
        Map<String, String> original = new HashMap<>();
        original.put("userId", "10086");
        original.put("ip", "192.168.1.1");
        original.put("errorCode", "AUTH_FAIL");

        String json = JsonUtil.mapToJson(original);
        Map<String, String> parsed = JsonUtil.jsonToMap(json);

        assertEquals(original, parsed);
    }

    @Test
    public void escape_specialChars_isEscaped() {
        // 双引号、反斜杠、换行应被转义
        String escaped = JsonUtil.escape("a\"b\\c\nd");
        assertTrue(escaped.contains("\\\""));
        assertTrue(escaped.contains("\\\\"));
        assertTrue(escaped.contains("\\n"));
    }

    @Test
    public void escape_null_returnsEmpty() {
        assertEquals("", JsonUtil.escape(null));
    }

    @Test
    public void escape_plainText_returnsAsIs() {
        assertEquals("hello world", JsonUtil.escape("hello world"));
    }

    @Test
    public void toJson_object_returnsJson() {
        // 简单对象序列化
        String json = JsonUtil.toJson("hello");
        assertEquals("\"hello\"", json);
    }

    @Test
    public void toJson_null_returnsNullLiteral() {
        assertEquals("null", JsonUtil.toJson(null));
    }

    // ===== userFieldMemberJson（T-03 / ISSUE-07）=====

    @Test
    public void userFieldMemberJson_normal_returnsQuotedMember() {
        assertEquals("\"k\":\"v\"", JsonUtil.userFieldMemberJson("k", "v"));
    }

    @Test
    public void userFieldMemberJson_nullValue_returnsNullLiteral() {
        assertEquals("\"k\":null", JsonUtil.userFieldMemberJson("k", null));
    }

    @Test
    public void userFieldMemberJson_emptyValue_returnsEmptyStringMember() {
        assertEquals("\"k\":\"\"", JsonUtil.userFieldMemberJson("k", ""));
    }

    @Test
    public void userFieldMemberJson_specialChars_matchesWriteEncoding() {
        // 键值含引号/反斜杠/换行时，成员片段必须与写入 mapToJson 的转义表示一致
        String key = "k\"ey\\x";
        String value = "a\"b\\c\nd";
        String member = JsonUtil.userFieldMemberJson(key, value);
        String written = JsonUtil.mapToJson(Collections.singletonMap(key, value));
        assertTrue("member [" + member + "] should be contained in written [" + written + "]",
                written.contains(member));
    }

    @Test
    public void userFieldMemberJson_nullValue_matchesWriteEncoding() {
        // serializeNulls 配置下，写入的 null 值与成员片段的 null 字面量一致
        Map<String, String> map = new HashMap<>();
        map.put("k", null);
        String written = JsonUtil.mapToJson(map);
        assertTrue(written.contains(JsonUtil.userFieldMemberJson("k", null)));
    }

    @Test
    public void userFieldMemberJson_plainMember_notContainedInNullValueRow() {
        // null 值行不应被非 null 成员误匹配
        String nullRow = JsonUtil.mapToJson(Collections.singletonMap("k", (String) null));
        assertFalse(nullRow.contains(JsonUtil.userFieldMemberJson("k", "null")));
    }
}
