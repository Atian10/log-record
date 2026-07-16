package com.atian10.logrecord.core;

import com.atian10.logrecord.core.model.LogRecord;

/**
 * 日志过滤器接口
 * <p>
 * 业务方可实现此接口自定义过滤规则（按 type/tag 等过滤）。
 * 返回 false 的日志不写入存储。
 * </p>
 */
public interface ILogFilter {

    /**
     * 是否允许写入
     * @param record 日志记录
     * @return true 允许写入，false 拒绝
     */
    boolean accept(LogRecord record);
}
