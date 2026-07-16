package com.atian10.logrecord.core.engine;

import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

/**
 * 队列条目包装类
 * <p>
 * 引擎使用 {@code BlockingQueue<LogEntry>} 统一存放日志和异常两类记录。
 * 通过 {@link #type()} 区分具体类型，避免 {@code instanceof} 判断。
 * 不可变对象，构造后只读。
 * </p>
 */
public final class LogEntry {

    /**
     * 条目类型
     */
    public enum Type {
        /** 日志记录 */
        LOG,
        /** 异常记录 */
        EXCEPTION
    }

    private final Type type;
    private final LogRecord logRecord;
    private final ExceptionRecord exceptionRecord;

    private LogEntry(Type type, LogRecord logRecord, ExceptionRecord exceptionRecord) {
        this.type = type;
        this.logRecord = logRecord;
        this.exceptionRecord = exceptionRecord;
    }

    /**
     * 包装日志记录
     * @param record 日志记录，不可为 null
     * @return 日志类型条目
     */
    public static LogEntry of(LogRecord record) {
        if (record == null) {
            throw new NullPointerException("record == null");
        }
        return new LogEntry(Type.LOG, record, null);
    }

    /**
     * 包装异常记录
     * @param record 异常记录，不可为 null
     * @return 异常类型条目
     */
    public static LogEntry of(ExceptionRecord record) {
        if (record == null) {
            throw new NullPointerException("record == null");
        }
        return new LogEntry(Type.EXCEPTION, null, record);
    }

    /**
     * 获取条目类型
     * @return 类型枚举
     */
    public Type type() {
        return type;
    }

    /**
     * 是否为日志条目
     * @return true 表示日志条目
     */
    public boolean isLog() {
        return type == Type.LOG;
    }

    /**
     * 是否为异常条目
     * @return true 表示异常条目
     */
    public boolean isException() {
        return type == Type.EXCEPTION;
    }

    /**
     * 获取日志记录
     * @return 日志记录；若本条目为异常类型则返回 null
     */
    public LogRecord logRecord() {
        return logRecord;
    }

    /**
     * 获取异常记录
     * @return 异常记录；若本条目为日志类型则返回 null
     */
    public ExceptionRecord exceptionRecord() {
        return exceptionRecord;
    }
}
