package com.atian10.logrecord.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;

/**
 * JSON 工具类
 * <p>
 * 基于 Gson 实现，用于用户键值对（userFields）序列化与反序列化，
 * 以及导出 JSON 格式时的对象序列化。内部持有共享 Gson 实例（线程安全）。
 * </p>
 */
public final class JsonUtil {

    /** 共享 Gson 实例（Gson 本身线程安全） */
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    /** Map<String,String> 类型 token */
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private JsonUtil() {
        // 工具类禁止实例化
    }

    /**
     * 将对象序列化为 JSON 字符串
     * @param src 待序列化对象
     * @return JSON 字符串；src 为 null 时返回 "null"
     */
    public static String toJson(Object src) {
        return GSON.toJson(src);
    }

    /**
     * 将 Map<String,String> 序列化为 JSON 字符串
     * @param map 键值对，可为 null
     * @return JSON 字符串；map 为 null 时返回 "{}"
     */
    public static String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        return GSON.toJson(map);
    }

    /**
     * 构造单个键值对的规范 JSON 成员片段（不含外层大括号）
     * <p>
     * 用于 userFields 查询条件：与 {@link #mapToJson} 使用同一 Gson 配置序列化
     * 单项键值，取出形如 {@code "key":"value"} 的成员片段（value 为 null 时为
     * {@code "key":null}）。存储层将对象外层大括号转换为逗号，并在本片段前后加逗号
     * 做完整成员匹配；裸片段不足以排除转义键名后缀。序列化配置与写入一致。
     * </p>
     * <p>适用范围限定为本库统一配置生成的字符串映射 JSON；外部导入的非规范
     * JSON 或其他值类型不经额外验证不承诺匹配。</p>
     *
     * @param key 字段名，不可为 null
     * @param value 字段值，可为 null（null 表示查询"值为 null"的精确匹配）
     * @return 规范成员片段，形如 {@code "key":"value"} 或 {@code "key":null}
     */
    public static String userFieldMemberJson(String key, String value) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }
        // 序列化单条目 Map 后去掉首尾大括号，得到与写入路径一致的成员表示
        String json = GSON.toJson(Collections.singletonMap(key, value));
        return json.substring(1, json.length() - 1);
    }

    /**
     * 将 JSON 字符串反序列化为 Map<String,String>
     * @param json JSON 字符串
     * @return 不可变 Map；json 为空或解析失败时返回空 Map
     */
    public static Map<String, String> jsonToMap(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> map = GSON.fromJson(json, MAP_TYPE);
            return map == null ? Collections.<String, String>emptyMap() : map;
        } catch (JsonSyntaxException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型对象
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T> 目标泛型
     * @return 反序列化对象；解析失败时返回 null
     */
    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, type);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符（用于手工拼接 JSON 场景）
     * @param text 原始文本
     * @return 转义后文本
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        // 借用 Gson 的字符串序列化能力（输出带双引号），再去掉首尾引号
        String quoted = GSON.toJson(text);
        return quoted.substring(1, quoted.length() - 1);
    }
}
