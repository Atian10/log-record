package com.atian10.logrecord.core;

import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;

/**
 * 日志格式化接口
 * <p>
 * 仅用于导出时格式化。写入时分列存储不需要 Formatter。
 * 实现方可自定义格式，抛异常时降级用原始内容。
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
