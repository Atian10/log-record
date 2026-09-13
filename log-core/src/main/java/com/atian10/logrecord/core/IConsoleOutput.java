package com.atian10.logrecord.core;

import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

/**
 * 平台控制台输出策略，不负责存储、开关、初始化或资源关闭。
 * <p>LogManager 在提交存储引擎后，以本次配置快照决定是否同步调用策略。
 * record 非空；formatter 为快照中的可空自定义格式化器，非空时使用 TXT，
 * 为空时使用输出通道的默认文本。实现须支持业务线程并发调用，不重入日志输出。
 * 普通运行时异常由管理器记录告警，不重试、不回退到另一输出通道。
 * </p>
 * <p>关闭后的新分发被跳过；关闭前已通过 RUNNING 检查的在途调用可结束，
 * shutdown 不等待策略完成，也不保证返回后在途调用不会再产生物理输出。
 * 使用自有接口，避免引入 Android API 24 的 java.util.function 依赖。</p>
 */
public interface IConsoleOutput {
    /** 输出一条普通记录；格式化器的选择与控制台开关来自同一快照。 */
    void print(LogRecord record, IFormatter formatter);

    /** 输出一条异常记录；平台策略将其映射到错误通道。 */
    void print(ExceptionRecord record, IFormatter formatter);
}
