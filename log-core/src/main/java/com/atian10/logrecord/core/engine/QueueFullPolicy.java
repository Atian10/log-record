package com.atian10.logrecord.core.engine;

/**
 * 队列满策略枚举
 * <p>
 * 当异步队列满时，决定如何处理新提交的日志。
 * </p>
 */
public enum QueueFullPolicy {
    /** 丢弃最旧日志（默认） */
    DROP_OLDEST,
    /** 丢弃最新日志（当前提交的） */
    DROP_NEWEST,
    /** 阻塞等待（慎用，可能阻塞业务线程） */
    BLOCK
}
