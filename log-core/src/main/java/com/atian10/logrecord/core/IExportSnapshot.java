package com.atian10.logrecord.core;

import java.util.List;

/**
 * 目标表的一致性导出快照
 * <p>
 * 由 {@link IStorage#openExportSnapshot(LogQuery)} /
 * {@link IExceptionStorage#openExportSnapshot(ExceptionQuery)} 创建。
 * 打开时在一次短读取边界内捕获目标表已提交的最大 ID 与匹配数量；
 * 之后按 {@code (timestamp, id)} 双键稳定次序分批返回记录，
 * 边界外的新增记录不进入快照，读取期间目标表的清理被互斥推迟。
 * </p>
 * <p>
 * <b>严格读取语义</b>：分批读取失败必须抛出 {@link ExportSnapshotException}，
 * 不得用空列表表示读取失败；返回空列表仅表示已读完。
 * </p>
 * <p>
 * 使用方必须在复制完成后调用 {@link #close()} 释放读取保护（幂等）。
 * 一致性保证仅覆盖本库管理的追加写入、清理与关闭；经 DAO、原始连接或
 * 其他进程对数据的修改不在保证范围内。
 * </p>
 *
 * @param <T> 记录类型（LogRecord 或 ExceptionRecord）
 */
public interface IExportSnapshot<T> {

    /**
     * 捕获边界内的匹配记录总数
     * <p>打开快照时确定，之后不变；边界外新增的记录不计入</p>
     */
    long getCapturedCount();

    /**
     * 读取下一批记录
     * <p>按 {@code (timestamp, id)} 双键稳定次序（次序由实现声明，升序或降序）返回；
     * 返回空列表表示已读完</p>
     *
     * @param maxRows 本批最大条数（正数）
     * @return 本批记录；已读完时为空列表（绝不为 null）
     * @throws ExportSnapshotException 读取失败（严格语义：失败不得伪装为空数据）
     */
    List<T> nextBatch(int maxRows);

    /**
     * 是否已读完捕获边界内的全部记录
     */
    boolean isExhausted();

    /**
     * 释放快照资源与读取保护（幂等，可多次调用）
     * <p>关闭后调用 {@link #nextBatch(int)} 行为未定义</p>
     */
    void close();
}
