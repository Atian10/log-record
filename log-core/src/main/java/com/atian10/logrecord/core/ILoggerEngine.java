package com.atian10.logrecord.core;

import com.atian10.logrecord.core.engine.LogStatus;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

/**
 * 日志引擎接口
 * <p>
 * 异步引擎的抽象，负责提交日志到队列、批处理写入、状态管理。
 * 引擎内部使用单线程 + BlockingQueue 实现，不阻塞业务线程。
 * </p>
 */
public interface ILoggerEngine {

    /**
     * 提交日志记录到队列
     * @param record 日志记录
     */
    void submit(LogRecord record);

    /**
     * 提交异常记录到队列
     * @param record 异常记录
     */
    void submit(ExceptionRecord record);

    /**
     * 立即落盘
     * <p>打破批处理周期，立即把队列剩余记录写入存储</p>
     */
    void flush();

    /**
     * 关闭引擎
     * <p>等待剩余日志写完后停止工作线程</p>
     */
    void shutdown();

    /**
     * 获取引擎状态
     * @return 库状态
     */
    LogStatus getStatus();
}
