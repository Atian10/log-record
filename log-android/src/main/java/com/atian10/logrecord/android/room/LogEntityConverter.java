package com.atian10.logrecord.android.room;

import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.util.JsonUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * LogRecord ↔ LogEntity 转换器
 * <p>
 * 负责领域模型与 Room Entity 的相互转换。
 * userFields 在 Entity 中以 JSON 字符串存储，转换时序列化/反序列化。
 * </p>
 */
public final class LogEntityConverter {

    private LogEntityConverter() {
        // 工具类禁止实例化
    }

    /**
     * LogRecord → LogEntity（用于写入）
     * @param record 日志记录
     * @return Room Entity
     */
    public static LogEntity toEntity(LogRecord record) {
        if (record == null) {
            return null;
        }
        LogEntity entity = new LogEntity();
        entity.id = 0; // 自增主键，0 表示由数据库生成
        entity.timestamp = record.getTimestamp();
        entity.level = record.getLevel() == null ? 0 : record.getLevel().getValue();
        entity.type = record.getType();
        entity.tag = record.getTag();
        entity.threadName = record.getThreadName();
        entity.threadId = record.getThreadId();
        entity.methodName = record.getMethodName();
        entity.lineNumber = record.getLineNumber();
        entity.message = record.getMessage();
        entity.userFields = JsonUtil.mapToJson(record.getUserFields());
        entity.versionTag = record.getVersionTag();
        entity.hasException = record.isHasException() ? 1 : 0;
        return entity;
    }

    /**
     * LogEntity → LogRecord（用于查询结果）
     * @param entity Room Entity
     * @return 日志记录
     */
    public static LogRecord toRecord(LogEntity entity) {
        if (entity == null) {
            return null;
        }
        LogLevel level = LogLevel.fromValue(entity.level);
        Map<String, String> userFields = JsonUtil.jsonToMap(entity.userFields);
        // 转换为可变 Map（LogRecord 构造时会包装为不可变视图）
        Map<String, String> mutableFields = userFields.isEmpty()
                ? null : new HashMap<>(userFields);
        return new LogRecord(
                entity.timestamp,
                level,
                entity.type,
                entity.tag,
                entity.threadName,
                entity.threadId,
                entity.methodName,
                entity.lineNumber,
                entity.versionTag,
                entity.hasException == 1,
                entity.message,
                mutableFields
        );
    }
}
