package com.atian10.logrecord.core.formatter;

import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.util.JsonUtil;
import com.atian10.logrecord.core.util.TimeUtil;

import java.util.Map;

/**
 * 默认格式化器
 * <p>
 * 实现 {@link IFormatter} 接口，支持 TXT/JSON/CSV 三种导出格式。
 * 仅用于导出场景，写入时分列存储不经过 Formatter。
 * </p>
 */
public final class DefaultFormatter implements IFormatter {

    /** CSV 表头（日志） */
    private static final String LOG_CSV_HEADER =
            "timestamp,level,type,tag,threadName,threadId,methodName,lineNumber,"
                    + "versionTag,hasException,message,userFields";

    /** CSV 表头（异常） */
    private static final String EXCEPTION_CSV_HEADER =
            "timestamp,exceptionClass,exceptionMessage,stackTrace,logTag";

    @Override
    public String format(LogRecord record, ExportFormat format) {
        if (record == null) {
            return "";
        }
        try {
            switch (format) {
                case TXT:
                    return formatLogTxt(record);
                case JSON:
                    return formatLogJson(record);
                case CSV:
                    return formatLogCsv(record);
                default:
                    return record.toString();
            }
        } catch (Exception e) {
            // 格式化失败降级用 toString，保证导出不中断
            return record.toString();
        }
    }

    @Override
    public String format(ExceptionRecord record, ExportFormat format) {
        if (record == null) {
            return "";
        }
        try {
            switch (format) {
                case TXT:
                    return formatExceptionTxt(record);
                case JSON:
                    return formatExceptionJson(record);
                case CSV:
                    return formatExceptionCsv(record);
                default:
                    return record.toString();
            }
        } catch (Exception e) {
            return record.toString();
        }
    }

    /**
     * 获取日志 CSV 表头
     */
    public String getLogCsvHeader() {
        return LOG_CSV_HEADER;
    }

    /**
     * 获取异常 CSV 表头
     */
    public String getExceptionCsvHeader() {
        return EXCEPTION_CSV_HEADER;
    }

    // ===== 日志格式化 =====

    private String formatLogTxt(LogRecord r) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(TimeUtil.formatUtc(r.getTimestamp())).append(']');
        sb.append(' ').append(levelName(r.getLevel()));
        sb.append('/').append(safe(r.getType()));
        sb.append('[').append(safe(r.getTag())).append(']');
        sb.append(" [").append(safe(r.getThreadName())).append(':').append(r.getThreadId()).append(']');
        if (r.getMethodName() != null) {
            sb.append(" (").append(r.getMethodName()).append(':').append(r.getLineNumber()).append(')');
        }
        if (r.getVersionTag() != null) {
            sb.append(" <v=").append(r.getVersionTag()).append('>');
        }
        if (r.isHasException()) {
            sb.append(" <hasException>");
        }
        sb.append(": ").append(safe(r.getMessage()));
        Map<String, String> uf = r.getUserFields();
        if (uf != null && !uf.isEmpty()) {
            sb.append(" ").append(uf);
        }
        return sb.toString();
    }

    private String formatLogJson(LogRecord r) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendJsonField(sb, "timestamp", r.getTimestamp(), true);
        appendJsonField(sb, "time", TimeUtil.formatUtc(r.getTimestamp()), true);
        appendJsonField(sb, "level", levelName(r.getLevel()), true);
        appendJsonField(sb, "type", r.getType(), true);
        appendJsonField(sb, "tag", r.getTag(), true);
        appendJsonField(sb, "threadName", r.getThreadName(), true);
        appendJsonField(sb, "threadId", r.getThreadId(), true);
        appendJsonField(sb, "methodName", r.getMethodName(), true);
        appendJsonField(sb, "lineNumber", r.getLineNumber(), true);
        appendJsonField(sb, "versionTag", r.getVersionTag(), true);
        appendJsonField(sb, "hasException", r.isHasException(), true);
        appendJsonField(sb, "message", r.getMessage(), false);
        sb.append(",\"userFields\":").append(JsonUtil.mapToJson(r.getUserFields()));
        sb.append('}');
        return sb.toString();
    }

    private String formatLogCsv(LogRecord r) {
        StringBuilder sb = new StringBuilder(128);
        appendCsvField(sb, r.getTimestamp());
        appendCsvField(sb, levelName(r.getLevel()));
        appendCsvField(sb, r.getType());
        appendCsvField(sb, r.getTag());
        appendCsvField(sb, r.getThreadName());
        appendCsvField(sb, r.getThreadId());
        appendCsvField(sb, r.getMethodName());
        appendCsvField(sb, r.getLineNumber());
        appendCsvField(sb, r.getVersionTag());
        appendCsvField(sb, r.isHasException());
        appendCsvField(sb, r.getMessage());
        appendCsvFieldLast(sb, JsonUtil.mapToJson(r.getUserFields()));
        return sb.toString();
    }

    // ===== 异常格式化 =====

    private String formatExceptionTxt(ExceptionRecord r) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(TimeUtil.formatUtc(r.getTimestamp())).append(']');
        sb.append(" EXCEPTION");
        if (r.getLogTag() != null) {
            sb.append('[').append(r.getLogTag()).append(']');
        }
        sb.append(": ").append(safe(r.getExceptionClass()));
        sb.append(": ").append(safe(r.getExceptionMessage()));
        sb.append('\n').append(safe(r.getStackTrace()));
        return sb.toString();
    }

    private String formatExceptionJson(ExceptionRecord r) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendJsonField(sb, "timestamp", r.getTimestamp(), true);
        appendJsonField(sb, "time", TimeUtil.formatUtc(r.getTimestamp()), true);
        appendJsonField(sb, "exceptionClass", r.getExceptionClass(), true);
        appendJsonField(sb, "exceptionMessage", r.getExceptionMessage(), true);
        appendJsonField(sb, "logTag", r.getLogTag(), true);
        appendJsonField(sb, "stackTrace", r.getStackTrace(), false);
        sb.append('}');
        return sb.toString();
    }

    private String formatExceptionCsv(ExceptionRecord r) {
        StringBuilder sb = new StringBuilder(128);
        appendCsvField(sb, r.getTimestamp());
        appendCsvField(sb, r.getExceptionClass());
        appendCsvField(sb, r.getExceptionMessage());
        appendCsvField(sb, r.getStackTrace());
        appendCsvFieldLast(sb, r.getLogTag());
        return sb.toString();
    }

    // ===== 辅助方法 =====

    private static String levelName(LogLevel level) {
        return level == null ? "null" : level.name();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean hasMore) {
        sb.append('"').append(key).append("\":");
        sb.append('"').append(JsonUtil.escape(value)).append('"');
        if (hasMore) {
            sb.append(',');
        }
    }

    private static void appendJsonField(StringBuilder sb, String key, long value, boolean hasMore) {
        sb.append('"').append(key).append("\":").append(value);
        if (hasMore) {
            sb.append(',');
        }
    }

    private static void appendJsonField(StringBuilder sb, String key, boolean value, boolean hasMore) {
        sb.append('"').append(key).append("\":").append(value);
        if (hasMore) {
            sb.append(',');
        }
    }

    private static void appendCsvField(StringBuilder sb, Object value) {
        sb.append(csvEscape(value)).append(',');
    }

    private static void appendCsvFieldLast(StringBuilder sb, Object value) {
        sb.append(csvEscape(value));
    }

    private static String csvEscape(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        // CSV 规范：含逗号、引号、换行的字段需用双引号包裹，内部双引号转义为两个双引号
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
