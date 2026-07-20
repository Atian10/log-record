package com.atian10.logrecord.core.util;

/**
 * SQL LIKE 模式转义工具
 * <p>
 * SQLite LIKE 通配符：{@code %}（任意字符序列）、{@code _}（单个字符）、
 * 转义符默认 {@code \}。业务方传入的 keyword/userFields 值若包含这些字符，
 * 不转义会被解释为通配符，导致查询结果不准确或注入风险。
 * </p>
 * <p>
 * 用法：在 SQL 中使用 {@code LIKE ? ESCAPE '\'}，参数用本工具转义后再用
 * {@code %} 包裹形成模糊匹配。
 * </p>
 */
public final class LikeEscapeUtil {

    /** 默认转义字符 */
    public static final char ESCAPE_CHAR = '\\';

    private LikeEscapeUtil() {
        // 工具类禁止实例化
    }

    /**
     * 转义 LIKE 模式中的特殊字符
     * <p>
     * 转义规则：{@code \} → {@code \\}，{@code %} → {@code \%}，{@code _} → {@code \_}
     * </p>
     * @param input 原始字符串；null 返回 null
     * @return 转义后的字符串
     */
    public static String escape(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(input.length() + 4);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                sb.append(ESCAPE_CHAR);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 构建模糊匹配模式（包含匹配）：{@code %escaped%}
     * @param input 原始字符串；null 返回 null
     * @return 形如 {@code %escaped%} 的 LIKE 模式
     */
    public static String contains(String input) {
        if (input == null) {
            return null;
        }
        return "%" + escape(input) + "%";
    }
}
