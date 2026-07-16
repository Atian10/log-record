package com.atian10.logrecord.core.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 时间工具类
 * <p>
 * 统一时间获取与格式化。所有日志时间戳使用 UTC 毫秒数。
 * 导出/控制台展示时可按需转换为可读字符串。
 * </p>
 */
public final class TimeUtil {

    /** UTC 时区 */
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** 本地默认时区 */
    private static final TimeZone LOCAL = TimeZone.getDefault();

    private TimeUtil() {
        // 工具类禁止实例化
    }

    /**
     * 获取当前 UTC 时间戳（毫秒）
     * @return UTC 毫秒数
     */
    public static long now() {
        return System.currentTimeMillis();
    }

    /**
     * 将时间戳格式化为 UTC 字符串（ISO8601 风格）
     * @param timestampMillis 时间戳（毫秒）
     * @return 形如 "2026-07-16 08:30:00.123"
     */
    public static String formatUtc(long timestampMillis) {
        return format(timestampMillis, "yyyy-MM-dd HH:mm:ss.SSS", UTC);
    }

    /**
     * 将时间戳格式化为本地时区字符串
     * @param timestampMillis 时间戳（毫秒）
     * @return 形如 "2026-07-16 16:30:00.123"
     */
    public static String formatLocal(long timestampMillis) {
        return format(timestampMillis, "yyyy-MM-dd HH:mm:ss.SSS", LOCAL);
    }

    /**
     * 自定义格式与时区格式化
     * @param timestampMillis 时间戳（毫秒）
     * @param pattern 日期格式（如 "yyyy-MM-dd HH:mm:ss.SSS"）
     * @param timeZone 时区
     * @return 格式化字符串
     */
    public static String format(long timestampMillis, String pattern, TimeZone timeZone) {
        SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
        sdf.setTimeZone(timeZone == null ? UTC : timeZone);
        return sdf.format(new Date(timestampMillis));
    }

    /**
     * 计算指定天数之前的时间戳
     * @param days 天数
     * @return 指定天数之前的时间戳（毫秒）
     */
    public static long millisBeforeDays(int days) {
        return now() - (long) days * 24L * 60L * 60L * 1000L;
    }
}
