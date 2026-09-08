package com.atian10.logrecord.core;

/**
 * 导出快照读取失败异常
 * <p>
 * 快照打开或分批读取失败时抛出，用于区分"读取失败"与"没有数据"的严格语义；
 * 导出器据此终止导出并报告失败，不得把失败当作空集合处理。
 * </p>
 */
public class ExportSnapshotException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造快照读取失败异常
     *
     * @param message 失败描述
     * @param cause 失败原因
     */
    public ExportSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
