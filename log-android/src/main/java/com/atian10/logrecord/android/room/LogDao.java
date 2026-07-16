package com.atian10.logrecord.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.List;

/**
 * 日志 DAO
 * <p>
 * 提供日志表的增删查统能力。动态查询使用 {@link RawQuery} 配合 SupportSQLiteQuery，
 * 由 {@code RoomStorage} 负责拼接 SQL 与参数。
 * </p>
 */
@Dao
public interface LogDao {

    /**
     * 插入单条日志
     * @param entity 日志实体
     * @return 自增主键 id
     */
    @Insert
    long insert(LogEntity entity);

    /**
     * 批量插入日志（事务）
     * @param entities 日志实体列表
     * @return 插入的主键数组
     */
    @Insert
    long[] insertAll(List<LogEntity> entities);

    /**
     * 动态查询日志
     * @param query SQL 查询（含 WHERE/ORDER BY/LIMIT OFFSET）
     * @return 日志实体列表
     */
    @RawQuery
    List<LogEntity> query(SupportSQLiteQuery query);

    /**
     * 动态查询符合条件的记录数
     * @param query SQL 查询（SELECT COUNT(*) ...）
     * @return 记录数
     */
    @RawQuery
    long count(SupportSQLiteQuery query);

    /**
     * 查询日志总记录数
     * @return 总记录数
     */
    @Query("SELECT COUNT(*) FROM log_record")
    long countAll();

    /**
     * 清理指定时间之前的日志
     * @param timestamp 时间戳（毫秒），清理 timestamp < 此值的记录
     * @return 清理的记录数
     */
    @Query("DELETE FROM log_record WHERE timestamp < :timestamp")
    int cleanBefore(long timestamp);

    /**
     * 按保留数量清理（保留最新的 N 条，按 timestamp DESC 排序）
     * @param keepCount 保留数量
     * @return 清理的记录数
     */
    @Query("DELETE FROM log_record WHERE id NOT IN ("
            + "SELECT id FROM log_record ORDER BY timestamp DESC LIMIT :keepCount)")
    int cleanByCount(int keepCount);

    /**
     * 按级别聚合统计（全表）
     * @return 级别计数列表
     */
    @Query("SELECT level, COUNT(*) AS count FROM log_record GROUP BY level")
    List<LevelCount> countByLevel();

    /**
     * 按类型聚合统计（全表）
     * @return 类型计数列表
     */
    @Query("SELECT type, COUNT(*) AS count FROM log_record GROUP BY type")
    List<TypeCount> countByType();

    /**
     * 按标签聚合统计（全表）
     * @return 标签计数列表
     */
    @Query("SELECT tag, COUNT(*) AS count FROM log_record GROUP BY tag")
    List<TagCount> countByTag();

    /**
     * 删除全部日志（慎用）
     * @return 删除的记录数
     */
    @Query("DELETE FROM log_record")
    int deleteAll();

    /**
     * 级别计数 POJO（Room 结果映射）
     */
    class LevelCount {
        public int level;
        public long count;
    }

    /**
     * 类型计数 POJO
     */
    class TypeCount {
        public String type;
        public long count;
    }

    /**
     * 标签计数 POJO
     */
    class TagCount {
        public String tag;
        public long count;
    }
}
