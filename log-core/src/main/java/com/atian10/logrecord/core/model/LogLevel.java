package com.atian10.logrecord.core.model;

/**
 * 日志级别枚举
 * <p>
 * 共 5 个级别，所有级别强制写入存储，不支持级别门槛过滤。
 * </p>
 */
public enum LogLevel {

    /** 调试信息 */
    DEBUG(1),

    /** 普通信息 */
    INFO(2),

    /** 警告信息 */
    WARN(3),

    /** 错误信息 */
    ERROR(4),

    /** 致命错误 */
    FATAL(5);

    private final int value;

    LogLevel(int value) {
        this.value = value;
    }

    /**
     * 获取级别数值
     * @return 级别数值（1-5）
     */
    public int getValue() {
        return value;
    }

    /**
     * 根据数值解析级别
     * @param value 级别数值
     * @return 对应的日志级别，数值非法时返回 null
     */
    public static LogLevel fromValue(int value) {
        for (LogLevel level : values()) {
            if (level.value == value) {
                return level;
            }
        }
        return null;
    }
}
