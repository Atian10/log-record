package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;

import java.util.List;

/**
 * 日志存储抽象接口
 * <p>
 * 定义日志的写入、查询、清理、容量信息能力。
 * 各平台实现此接口（Android Room / 桌面 JDBC）。
 * 系统字段与用户内容分列存储，异常存独立表（见 {@link IExceptionStorage}）。
 * </p>
 */
public interface IStorage {

    /**
     * 写入单条日志
     * <p>
     * 写入失败时应抛出异常（推荐 {@link BatchWriteException} 携带已保存数量），
     * 由异步引擎统一容错计数；不应静默吞掉失败。
     * </p>
     * @param record 日志记录
     */
    void write(LogRecord record);

    /**
     * 批量写入日志（异步引擎攒批后调用）
     * <p>
     * 写入失败时应抛出异常（推荐 {@link BatchWriteException} 携带已保存数量）。
     * 批量降级重试由存储实现自行决定并在内部完成后上报最终数量；
     * 提交结果不确定的事务禁止自动重放，避免重复写入。
     * </p>
     * @param records 日志记录列表
     */
    void writeBatch(List<LogRecord> records);

    /**
     * 查询日志
     * @param query 查询条件
     * @return 符合条件的日志列表
     */
    List<LogRecord> query(LogQuery query);

    /**
     * 聚合统计
     * @param query 查询条件
     * @return 统计结果
     */
    LogStatistics statistics(LogQuery query);

    /**
     * 按清理策略清理日志
     * @param policy 清理策略
     * @return 清理的记录数
     */
    int clean(CleanPolicy policy);

    /**
     * 清理指定时间之前的日志
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
     * 获取日志总记录数
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
    long count(LogQuery query);

    /**
     * 打开日志表的一致性导出快照
     * <p>
     * 打开时捕获目标表已提交的最大 ID 边界与匹配数量；读取期间按
     * {@code (timestamp, id)} 双键稳定次序分批返回，边界外新增不进入快照，
     * 目标表清理被互斥推迟。导出必须使用本能力保证集合一致性。
     * </p>
     * <p>
     * 默认实现明确报告不支持（{@link UnsupportedOperationException}）：
     * 自定义存储若要继续支持导出，需实现本方法提供快照适配；
     * 导出器不会静默回退到不可靠的 OFFSET 分页。
     * </p>
     *
     * @param query 查询条件（分页参数被忽略，排序由快照固定）
     * @return 导出快照；使用方负责 {@link IExportSnapshot#close()}
     * @throws UnsupportedOperationException 存储未提供快照适配
     * @throws ExportSnapshotException 边界捕获失败
     */
    default IExportSnapshot<LogRecord> openExportSnapshot(LogQuery query) {
        throw new UnsupportedOperationException(
                "storage does not implement openExportSnapshot; adapt it to keep export available");
    }
}
