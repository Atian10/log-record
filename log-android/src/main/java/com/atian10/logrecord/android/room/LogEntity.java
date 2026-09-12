package com.atian10.logrecord.android.room;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 日志主表 Entity（对应 log_record 表）
 * <p>
 * 严格对齐功能文档第十五章数据库表结构定义。列名通过 ColumnInfo 显式指定为 snake_case，
 * 保证与 SQL schema 一致（Room 默认不自动转换驼峰为下划线）。
 * 系统字段与用户内容分列存储，user_fields 以 JSON 字符串保存。
 * </p>
 */
@Entity(
        tableName = "log_record",
        indices = {
                @Index(value = "timestamp", name = "idx_log_timestamp"),
                @Index(value = "level", name = "idx_log_level"),
                @Index(value = "type", name = "idx_log_type"),
                @Index(value = "tag", name = "idx_log_tag"),
                @Index(value = "version_tag", name = "idx_log_version_tag")
        }
)
public final class LogEntity {

    /** 自增主键 */
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 时间戳（UTC 毫秒） */
    public long timestamp;

    /** 级别数值（1-5） */
    public int level;

    /** 类型 */
    public String type;

    /** 标签 */
    public String tag;

    /** 线程名 */
    @ColumnInfo(name = "thread_name")
    public String threadName;

    /** 线程ID */
    @ColumnInfo(name = "thread_id")
    public long threadId;

    /** 方法名（可配置捕获，未开启时为 null） */
    @ColumnInfo(name = "method_name")
    public String methodName;

    /** 行号（可配置捕获，未开启时为 0） */
    @ColumnInfo(name = "line_number")
    public int lineNumber;

    /** 用户消息（纯文本） */
    public String message;

    /** 用户键值对（JSON 字符串） */
    @ColumnInfo(name = "user_fields")
    public String userFields;

    /** 版本标签（可空） */
    @ColumnInfo(name = "version_tag")
    public String versionTag;

    /** 是否伴随异常（0=否，1=是） */
    @ColumnInfo(name = "has_exception")
    public int hasException;

    /**
     * 无参构造，供 Room 生成的映射代码及转换器创建实体。
     */
    public LogEntity() {
    }

    /**
     * 全参构造，供调用方手动创建实体；Room 映射时忽略此构造。
     */
    @Ignore
    public LogEntity(long id, long timestamp, int level, String type, String tag,
                     String threadName, long threadId, String methodName, int lineNumber,
                     String message, String userFields, String versionTag, int hasException) {
        this.id = id;
        this.timestamp = timestamp;
        this.level = level;
        this.type = type;
        this.tag = tag;
        this.threadName = threadName;
        this.threadId = threadId;
        this.methodName = methodName;
        this.lineNumber = lineNumber;
        this.message = message;
        this.userFields = userFields;
        this.versionTag = versionTag;
        this.hasException = hasException;
    }
}
