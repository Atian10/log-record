package com.atian10.logrecord.core.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 日志数据模型
 * <p>
 * 不可变对象，构造后只读。包含系统字段（自动捕获）和用户内容（业务方提供）。
 * 系统字段与用户内容分列存储，便于查询和导出。
 * </p>
 */
public final class LogRecord {

    // ===== 系统字段（自动捕获） =====

    /** 时间戳（UTC 毫秒） */
    private final long timestamp;

    /** 日志级别 */
    private final LogLevel level;

    /** 日志类型（预设或自定义字符串） */
    private final String type;

    /** 标签 */
    private final String tag;

    /** 线程名 */
    private final String threadName;

    /** 线程 ID */
    private final long threadId;

    /** 方法名（可配置捕获，未开启时为 null） */
    private final String methodName;

    /** 行号（可配置捕获，未开启时为 0） */
    private final int lineNumber;

    /** 版本标签（语义由业务方自定义，可空） */
    private final String versionTag;

    /** 是否伴随异常（仅标记，异常详情存独立表） */
    private final boolean hasException;

    // ===== 用户内容（业务方提供） =====

    /** 用户消息（纯文本） */
    private final String message;

    /** 用户键值对（JSON 存储，不可变视图） */
    private final Map<String, String> userFields;

    /**
     * 构造日志记录
     */
    public LogRecord(long timestamp, LogLevel level, String type, String tag,
                     String threadName, long threadId, String methodName, int lineNumber,
                     String versionTag, boolean hasException,
                     String message, Map<String, String> userFields) {
        this.timestamp = timestamp;
        this.level = level;
        this.type = type;
        this.tag = tag;
        this.threadName = threadName;
        this.threadId = threadId;
        this.methodName = methodName;
        this.lineNumber = lineNumber;
        this.versionTag = versionTag;
        this.hasException = hasException;
        this.message = message;
        // 用户键值对包装为不可变视图
        this.userFields = userFields == null
                ? null
                : Collections.unmodifiableMap(userFields);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getType() {
        return type;
    }

    public String getTag() {
        return tag;
    }

    public String getThreadName() {
        return threadName;
    }

    public long getThreadId() {
        return threadId;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public boolean isHasException() {
        return hasException;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 获取用户键值对（不可变视图）
     * @return 不可变 Map，无用户字段时返回 null
     */
    public Map<String, String> getUserFields() {
        return userFields;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogRecord)) return false;
        LogRecord that = (LogRecord) o;
        return timestamp == that.timestamp
                && threadId == that.threadId
                && lineNumber == that.lineNumber
                && hasException == that.hasException
                && level == that.level
                && Objects.equals(type, that.type)
                && Objects.equals(tag, that.tag)
                && Objects.equals(threadName, that.threadName)
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(versionTag, that.versionTag)
                && Objects.equals(message, that.message)
                && Objects.equals(userFields, that.userFields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, level, type, tag, threadName, threadId,
                methodName, lineNumber, versionTag, hasException, message, userFields);
    }

    @Override
    public String toString() {
        return "LogRecord{"
                + "timestamp=" + timestamp
                + ", level=" + level
                + ", type='" + type + '\''
                + ", tag='" + tag + '\''
                + ", threadName='" + threadName + '\''
                + ", threadId=" + threadId
                + ", methodName='" + methodName + '\''
                + ", lineNumber=" + lineNumber
                + ", versionTag='" + versionTag + '\''
                + ", hasException=" + hasException
                + ", message='" + message + '\''
                + ", userFields=" + userFields
                + '}';
    }
}
