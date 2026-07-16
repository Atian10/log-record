package com.atian10.logrecord.core.export;

/**
 * 导出回调接口
 * <p>
 * 导出过程可能耗时较长（分页查询 + 文件写入），通过回调通知调用方进度与结果。
 * 回调方法在导出执行线程触发，调用方应避免在回调中执行耗时操作。
 * </p>
 */
public interface ExportCallback {

    /**
     * 导出进度通知
     * @param exported 已导出条数
     * @param total 总条数（-1 表示总数未知）
     */
    void onProgress(int exported, int total);

    /**
     * 导出成功
     * @param filePath 导出文件绝对路径
     * @param totalCount 导出总条数
     */
    void onSuccess(String filePath, int totalCount);

    /**
     * 导出失败
     * @param error 失败异常
     * @param exportedCount 已导出条数（失败前）
     */
    void onFailure(Throwable error, int exportedCount);
}
