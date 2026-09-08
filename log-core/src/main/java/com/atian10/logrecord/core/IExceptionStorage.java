package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;

import java.util.List;

/**
 * 异常存储接口
 * <p>
 * 异常表与日志表完全独立，无外键关联。
 * 提供独立的写入、查询、清理、容量信息能力。
 * </p>
 */
public interface IExceptionStorage {

    /**
     * 写入单条异常
     * <p>
     * 写入失败时应抛出异常（推荐 {@link BatchWriteException} 携带已保存数量），
     * 由异步引擎统一容错计数；不应静默吞掉失败。
     * </p>
     * @param record 异常记录
     */
    void write(ExceptionRecord record);

    /**
     * 批量写入异常（异步引擎攒批后调用）
     * <p>
     * 写入失败时应抛出异常（推荐 {@link BatchWriteException} 携带已保存数量）。
     * 批量降级重试由存储实现自行决定并在内部完成后上报最终数量；
     * 提交结果不确定的事务禁止自动重放，避免重复写入。
     * </p>
     * @param records 异常记录列表
     */
    void writeBatch(List<ExceptionRecord> records);

    /**
     * 查询异常
     * @param query 查询条件
     * @return 符合条件的异常列表
     */
    List<ExceptionRecord> query(ExceptionQuery query);

    /**
     * 按清理策略清理异常
     * @param policy 清理策略
     * @return 清理的记录数
     */
    int clean(CleanPolicy policy);

    /**
     * 清理指定时间之前的异常
     * @param timestamp 时间戳（毫秒），清理所有 timestamp 小于此值的记录
     * @return 清理的记录数
     */
    int cleanBefore(long timestamp);

    /**
     * 按保留数量清理（保留最新的 N 条）
     * @param keepCount 保留数量
     * @return 清理的记录数
     */
    int cleanByCount(int keepCount);

    /**
     * 获取异常总记录数
     * @return 记录数
     */
    long getRecordCount();

    /**
     * 获取数据库大小（字节）
     * @return 字节数
     */
    long getDbSizeBytes();

    /**
     * 查询符合条件的记录数（用于导出分页计算）
     * @param query 查询条件
     * @return 记录数
     */
    long count(ExceptionQuery query);

    /**
     * 打开异常表的一致性导出快照
     * <p>
     * 语义与 {@link IStorage#openExportSnapshot(LogQuery)} 相同：ID 边界、
     * {@code (timestamp, id)} 双键稳定次序、清理互斥与严格读取。
     * 默认实现明确报告不支持；自定义存储需提供适配才能继续导出。
     * </p>
     *
     * @param query 查询条件（分页参数被忽略，排序由快照固定）
     * @return 导出快照；使用方负责 {@link IExportSnapshot#close()}
     * @throws UnsupportedOperationException 存储未提供快照适配
     * @throws ExportSnapshotException 边界捕获失败
     */
    default IExportSnapshot<ExceptionRecord> openExportSnapshot(ExceptionQuery query) {
        throw new UnsupportedOperationException(
                "exception storage does not implement openExportSnapshot; "
                        + "adapt it to keep export available");
    }
}
