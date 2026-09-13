package com.atian10.logrecord.core;

import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

/**
 * 日志格式化接口
 * <p>
 * 用于导出及控制台文本格式化，控制台使用 TXT；持久化分列存储不依赖 Formatter。
 * 实现须支持并发调用。导出时抛异常会使整次导出失败，保留原目标文件并报告失败回调；
 * 控制台的运行时异常只记录管理器告警，不重试，也不撤销已提交的持久化。
 * </p>
 */
public interface IFormatter {

    /**
     * 格式化单条日志
     * @param record 日志记录
     * @param format 导出格式
     * @return 格式化后的字符串
     */
    String format(LogRecord record, ExportFormat format);

    /**
     * 格式化单条异常
     * @param record 异常记录
     * @param format 导出格式
     * @return 格式化后的字符串
     */
    String format(ExceptionRecord record, ExportFormat format);
}
