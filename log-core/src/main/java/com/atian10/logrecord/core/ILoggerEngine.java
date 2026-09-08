package com.atian10.logrecord.core;

import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.core.engine.LogStatus;
import com.atian10.logrecord.core.engine.ShutdownResult;
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
     * 立即落盘（兼容入口，默认 5 秒等待）
     * <p>打破批处理周期，把队列剩余记录写入存储</p>
     */
    void flush();

    /**
     * 带超时的立即落盘
     * <p>
     * 只等待调用时刻已接收记录（目标序号及之前）全部到达终态；
     * 调用之后新增的记录不延长本次等待。失败或丢弃同样结束等待，
     * 结果见 {@link FlushResult}。空闲且无未完成记录时立即返回。
     * </p>
     * @param timeoutMillis 等待时限（毫秒），负数按 0 处理
     * @return 等待结果，含保存/失败/丢弃/未完成计数
     */
    FlushResult flush(long timeoutMillis);

    /**
     * 关闭引擎（兼容入口，默认 5 秒等待）
     * <p>等待剩余日志写完后停止工作线程</p>
     */
    void shutdown();

    /**
     * 带超时关闭引擎
     * <p>等价于 {@link #stopAccepting()} 后调用 {@link #awaitShutdown(long)}</p>
     * @param timeoutMillis 等待工作线程退出的时限（毫秒），负数按 0 处理
     * @return 关闭结果；TIMEOUT 时工作线程仍在后台排空
     */
    ShutdownResult shutdown(long timeoutMillis);

    /**
     * 停止接收新记录并唤醒工作线程
     * <p>幂等。调用后 submit 被丢弃并计入告警；队列排空由工作线程负责</p>
     */
    void stopAccepting();

    /**
     * 等待工作线程完成排空并退出
     * <p>应在 {@link #stopAccepting()} 之后调用；超时返回未完成状态，
     * 后台继续收尾，调用方不应立即释放底层资源</p>
     * @param timeoutMillis 等待时限（毫秒），负数按 0 处理
     * @return 关闭结果
     */
    ShutdownResult awaitShutdown(long timeoutMillis);

    /**
     * 工作线程是否已退出（排空是否可能仍在进行的判断前提）
     * @return true 表示工作线程已退出
     */
    boolean isTerminated();

    /**
     * 获取引擎状态
     * @return 库状态
     */
    LogStatus getStatus();
}
