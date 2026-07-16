package com.atian10.logrecord.core.model;

import java.util.Objects;

/**
 * 异常数据模型
 * <p>
 * 不可变对象，构造后只读。与日志表完全独立，无外键关联。
 * 包含异常类名、异常消息、完整堆栈、弱关联的 tag。
 * </p>
 */
public final class ExceptionRecord {

    /** 时间戳（UTC 毫秒） */
    private final long timestamp;

    /** 异常类名 */
    private final String exceptionClass;

    /** 异常消息 */
    private final String exceptionMessage;

    /** 完整堆栈字符串 */
    private final String stackTrace;

    /** 弱关联 tag（记录时的 tag，可选） */
    private final String logTag;

    /**
     * 构造异常记录
     */
    public ExceptionRecord(long timestamp, String exceptionClass, String exceptionMessage,
                           String stackTrace, String logTag) {
        this.timestamp = timestamp;
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
        this.stackTrace = stackTrace;
        this.logTag = logTag;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public String getLogTag() {
        return logTag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExceptionRecord)) return false;
        ExceptionRecord that = (ExceptionRecord) o;
        return timestamp == that.timestamp
                && Objects.equals(exceptionClass, that.exceptionClass)
                && Objects.equals(exceptionMessage, that.exceptionMessage)
                && Objects.equals(stackTrace, that.stackTrace)
                && Objects.equals(logTag, that.logTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, exceptionClass, exceptionMessage, stackTrace, logTag);
    }

    @Override
    public String toString() {
        return "ExceptionRecord{"
                + "timestamp=" + timestamp
                + ", exceptionClass='" + exceptionClass + '\''
                + ", exceptionMessage='" + exceptionMessage + '\''
                + ", stackTrace='" + stackTrace + '\''
                + ", logTag='" + logTag + '\''
                + '}';
    }
}
