package com.atian10.logrecord.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.List;

/**
 * 异常 DAO
 * <p>
 * 提供异常表的增删查统能力。与日志表独立，无外键关联。
 * </p>
 */
@Dao
public interface ExceptionDao {

    /**
     * 插入单条异常
     * @param entity 异常实体
     * @return 自增主键 id
     */
    @Insert
    long insert(ExceptionEntity entity);

    /**
     * 批量插入异常（事务）
     * @param entities 异常实体列表
     * @return 插入的主键数组
     */
    @Insert
    long[] insertAll(List<ExceptionEntity> entities);

    /**
     * 动态查询异常
     * @param query SQL 查询
     * @return 异常实体列表
     */
    @RawQuery
    List<ExceptionEntity> query(SupportSQLiteQuery query);

    /**
     * 动态查询单值（如 COUNT(*)、MAX(id) 等单列数值结果）
     * @param query SQL 查询（返回单行单列数值）
     * @return 数值结果；无行时按 0 处理
     */
    @RawQuery
    long queryLong(SupportSQLiteQuery query);

    /**
     * 动态查询符合条件的记录数
     * @param query SQL 查询（SELECT COUNT(*) ...）
     * @return 记录数
     */
    @RawQuery
    long count(SupportSQLiteQuery query);

    /**
     * 查询异常总记录数
     * @return 总记录数
     */
    @Query("SELECT COUNT(*) FROM exception_table")
    long countAll();

    /**
     * 清理指定时间之前的异常
     * @param timestamp 时间戳（毫秒）
     * @return 清理的记录数
     */
    @Query("DELETE FROM exception_table WHERE timestamp < :timestamp")
    int cleanBefore(long timestamp);

    /**
     * 按保留数量清理（保留最新的 N 条）
     * @param keepCount 保留数量
     * @return 清理的记录数
     */
    @Query("DELETE FROM exception_table WHERE id NOT IN ("
            + "SELECT id FROM exception_table ORDER BY timestamp DESC LIMIT :keepCount)")
    int cleanByCount(int keepCount);

    /**
     * 删除时间戳不晚于阈值的最旧一批记录（全库容量协调使用，按 (timestamp, id) 次序）
     * @param ts 时间戳上界（包含）
     * @param limit 本批上限
     * @return 删除的记录数
     */
    @Query("DELETE FROM exception_table WHERE id IN ("
            + "SELECT id FROM exception_table WHERE timestamp <= :ts "
            + "ORDER BY timestamp ASC, id ASC LIMIT :limit)")
    int deleteOldestUpTo(long ts, int limit);

    /**
     * 取最旧的 N 条时间戳（供容量协调计算跨表批次上界）
     * @param limit 条数
     * @return 时间戳列表（升序）
     */
    @Query("SELECT timestamp FROM exception_table ORDER BY timestamp ASC, id ASC LIMIT :limit")
    List<Long> oldestTimestamps(int limit);

    /**
     * 删除全部异常
     * @return 删除的记录数
     */
    @Query("DELETE FROM exception_table")
    int deleteAll();
}
