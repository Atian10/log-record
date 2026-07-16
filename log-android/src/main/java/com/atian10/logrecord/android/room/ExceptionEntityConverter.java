package com.atian10.logrecord.android.room;

import com.atian10.logrecord.core.model.ExceptionRecord;

/**
 * ExceptionRecord ↔ ExceptionEntity 转换器
 * <p>
 * 负责领域模型与 Room Entity 的相互转换。
 * 异常表字段简单（无 JSON），转换直接映射。
 * </p>
 */
public final class ExceptionEntityConverter {

    private ExceptionEntityConverter() {
        // 工具类禁止实例化
    }

    /**
     * ExceptionRecord → ExceptionEntity（用于写入）
     * @param record 异常记录
     * @return Room Entity
     */
    public static ExceptionEntity toEntity(ExceptionRecord record) {
        if (record == null) {
            return null;
        }
        ExceptionEntity entity = new ExceptionEntity();
        entity.id = 0; // 自增主键
        entity.timestamp = record.getTimestamp();
        entity.exceptionClass = record.getExceptionClass();
        entity.exceptionMsg = record.getExceptionMessage();
        entity.stackTrace = record.getStackTrace();
        entity.logTag = record.getLogTag();
        return entity;
    }

    /**
     * ExceptionEntity → ExceptionRecord（用于查询结果）
     * @param entity Room Entity
     * @return 异常记录
     */
    public static ExceptionRecord toRecord(ExceptionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ExceptionRecord(
                entity.timestamp,
                entity.exceptionClass,
                entity.exceptionMsg,
                entity.stackTrace,
                entity.logTag
        );
    }
}
