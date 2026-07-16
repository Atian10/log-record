package com.atian10.logrecord.core.clean;

/**
 * 清理回调接口
 * <p>
 * 定时清理任务执行完成后通过此回调通知调用方结果。
 * 回调方法在清理执行线程触发。
 * </p>
 */
public interface CleanCallback {

    /**
     * 清理成功
     * @param cleanedCount 清理掉的记录总数（日志 + 异常）
     */
    void onSuccess(int cleanedCount);

    /**
     * 清理失败
     * @param error 失败异常
     */
    void onFailure(Throwable error);
}
