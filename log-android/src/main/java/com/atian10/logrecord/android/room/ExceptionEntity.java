package com.atian10.logrecord.android.room;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 异常表 Entity（对应 exception_table 表）
 * <p>
 * 与日志表完全独立，无外键关联。严格对齐功能文档第十五章表结构。
 * 列名通过 ColumnInfo 显式指定为 snake_case。
 * </p>
 */
@Entity(
        tableName = "exception_table",
        indices = {
                @Index(value = "timestamp", name = "idx_exception_timestamp"),
                @Index(value = "log_tag", name = "idx_exception_log_tag"),
                @Index(value = "exception_class", name = "idx_exception_class")
        }
)
public final class ExceptionEntity {

    /** 自增主键 */
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 时间戳（UTC 毫秒，独立于日志表） */
    public long timestamp;

    /** 异常类名 */
    @ColumnInfo(name = "exception_class")
    public String exceptionClass;

    /** 异常消息（对应 SQL 列 exception_msg） */
    @ColumnInfo(name = "exception_msg")
    public String exceptionMsg;

    /** 完整堆栈 */
    @ColumnInfo(name = "stack_trace")
    public String stackTrace;

    /** 弱关联 tag（记录时的 tag，可选） */
    @ColumnInfo(name = "log_tag")
    public String logTag;

    /**
     * 默认构造（Room 反射需要）
     */
    public ExceptionEntity() {
    }

    /**
     * 全参构造（便于 Converter 创建）
     */
    public ExceptionEntity(long id, long timestamp, String exceptionClass,
                           String exceptionMsg, String stackTrace, String logTag) {
        this.id = id;
        this.timestamp = timestamp;
        this.exceptionClass = exceptionClass;
        this.exceptionMsg = exceptionMsg;
        this.stackTrace = stackTrace;
        this.logTag = logTag;
    }
}
