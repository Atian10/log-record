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
 * <p>
 * 引擎接收记录时通过 {@link #of(LogRecord, long)}/{@link #of(ExceptionRecord, long)}
 * 分配单调递增的接收序号（seq），用于 flush 等待边界与终态跟踪；
 * 外部直接使用无序号工厂时 seq 为 0，不参与等待边界。
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
    /** 引擎分配的接收序号；0 表示未分配（不参与 flush 等待边界） */
    private final long seq;

    private LogEntry(Type type, LogRecord logRecord, ExceptionRecord exceptionRecord, long seq) {
        this.type = type;
        this.logRecord = logRecord;
        this.exceptionRecord = exceptionRecord;
        this.seq = seq;
    }

    /**
     * 包装日志记录（不分配序号，seq=0）
     * @param record 日志记录，不可为 null
     * @return 日志类型条目
     */
    public static LogEntry of(LogRecord record) {
        return of(record, 0L);
    }

    /**
     * 包装日志记录并指定接收序号
     * @param record 日志记录，不可为 null
     * @param seq 引擎分配的接收序号，须为正数
     * @return 日志类型条目
     */
    public static LogEntry of(LogRecord record, long seq) {
        if (record == null) {
            throw new NullPointerException("record == null");
        }
        return new LogEntry(Type.LOG, record, null, seq);
    }

    /**
     * 包装异常记录（不分配序号，seq=0）
     * @param record 异常记录，不可为 null
     * @return 异常类型条目
     */
    public static LogEntry of(ExceptionRecord record) {
        return of(record, 0L);
    }

    /**
     * 包装异常记录并指定接收序号
     * @param record 异常记录，不可为 null
     * @param seq 引擎分配的接收序号，须为正数
     * @return 异常类型条目
     */
    public static LogEntry of(ExceptionRecord record, long seq) {
        if (record == null) {
            throw new NullPointerException("record == null");
        }
        return new LogEntry(Type.EXCEPTION, null, record, seq);
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

    /**
     * 获取引擎分配的接收序号
     * @return 接收序号；外部用无序号工厂构造时为 0
     */
    public long seq() {
        return seq;
    }
}
