package com.atian10.logrecord.core;

/**
 * 存储写入失败异常
 * <p>
 * 存储实现在写入（单条或批量）失败时抛出，向异步引擎传递可识别错误；
 * 引擎捕获后计入失败并继续容错运行，不会传播给业务调用方。
 * 携带本次尝试数量与已确认保存数量，供引擎区分“处理结束”与“保存成功”。
 * </p>
 * <p>
 * 存储层的批量降级（如批量失败后逐条重放）应在内部完成后，用本异常上报最终
 * 保存/失败数量；引擎不再叠加第二层重试。
 * </p>
 */
public class BatchWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 本次尝试写入的记录总数 */
    private final int attempted;
    /** 其中已确认保存成功的记录数（介于 0 与 attempted 之间） */
    private final int saved;

    /**
     * 构造写入失败异常
     *
     * @param attempted 本次尝试写入的记录总数
     * @param saved 其中已确认保存成功的记录数
     * @param message 失败描述
     * @param cause 失败原因，可为 null
     */
    public BatchWriteException(int attempted, int saved, String message, Throwable cause) {
        super(message, cause);
        this.attempted = attempted;
        this.saved = saved;
    }

    /** 本次尝试写入的记录总数 */
    public int getAttempted() {
        return attempted;
    }

    /** 其中已确认保存成功的记录数 */
    public int getSaved() {
        return saved;
    }
}
