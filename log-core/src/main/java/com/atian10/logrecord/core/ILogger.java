package com.atian10.logrecord.core;

import com.atian10.logrecord.core.model.LogLevel;

import java.util.Map;

/**
 * 日志写入接口
 * <p>
 * 业务方通过此接口写入日志。所有级别强制写入，不支持级别门槛过滤。
 * 提供基础级别方法、携带异常便捷方法、完整参数方法和异常独立写入方法。
 * </p>
 */
public interface ILogger {

    /**
     * 写入 DEBUG 级别日志
     * @param tag 标签
     * @param message 消息内容
     */
    void d(String tag, String message);

    /**
     * 写入 INFO 级别日志
     * @param tag 标签
     * @param message 消息内容
     */
    void i(String tag, String message);

    /**
     * 写入 WARN 级别日志
     * @param tag 标签
     * @param message 消息内容
     */
    void w(String tag, String message);

    /**
     * 写入 ERROR 级别日志
     * @param tag 标签
     * @param message 消息内容
     */
    void e(String tag, String message);

    /**
     * 写入 FATAL 级别日志
     * @param tag 标签
     * @param message 消息内容
     */
    void f(String tag, String message);

    /**
     * 写入 ERROR 级别日志并记录异常
     * <p>便捷方法，内部拆为两条独立记录：一条 ERROR 日志 + 一条异常记录</p>
     * @param tag 标签
     * @param message 消息内容
     * @param throwable 异常
     */
    void e(String tag, String message, Throwable throwable);

    /**
     * 写入 FATAL 级别日志并记录异常
     * <p>便捷方法，内部拆为两条独立记录：一条 FATAL 日志 + 一条异常记录</p>
     * @param tag 标签
     * @param message 消息内容
     * @param throwable 异常
     */
    void f(String tag, String message, Throwable throwable);

    /**
     * 完整参数写入（无异常、无用户键值对）
     * @param level 级别
     * @param type 类型
     * @param tag 标签
     * @param message 消息内容
     */
    void log(LogLevel level, String type, String tag, String message);

    /**
     * 完整参数写入（含用户键值对）
     * @param level 级别
     * @param type 类型
     * @param tag 标签
     * @param message 消息内容
     * @param userFields 用户键值对
     */
    void log(LogLevel level, String type, String tag, String message, Map<String, String> userFields);

    /**
     * 完整参数写入（含异常）
     * @param level 级别
     * @param type 类型
     * @param tag 标签
     * @param message 消息内容
     * @param throwable 异常
     */
    void log(LogLevel level, String type, String tag, String message, Throwable throwable);

    /**
     * 完整参数写入（含用户键值对和异常）
     * @param level 级别
     * @param type 类型
     * @param tag 标签
     * @param message 消息内容
     * @param userFields 用户键值对
     * @param throwable 异常
     */
    void log(LogLevel level, String type, String tag, String message,
             Map<String, String> userFields, Throwable throwable);

    /**
     * 异常独立写入（不伴随日志）
     * @param throwable 异常
     * @param tag 弱关联 tag
     */
    void recordException(Throwable throwable, String tag);

    /**
     * 立即落盘
     */
    void flush();

    /**
     * 关闭引擎，等待剩余日志写完
     */
    void shutdown();
}
