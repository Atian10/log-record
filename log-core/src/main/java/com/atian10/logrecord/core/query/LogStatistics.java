package com.atian10.logrecord.core.query;

import com.atian10.logrecord.core.model.LogLevel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 日志聚合统计结果
 * <p>
 * 不可变对象，按级别、类型、Tag 维度统计数量。
 * </p>
 */
public final class LogStatistics {

    /** 总记录数 */
    private final long totalCount;

    /** 按级别统计 */
    private final Map<LogLevel, Long> countByLevel;

    /** 按类型统计 */
    private final Map<String, Long> countByType;

    /** 按标签统计 */
    private final Map<String, Long> countByTag;

    /**
     * 构造统计结果
     */
    public LogStatistics(long totalCount,
                         Map<LogLevel, Long> countByLevel,
                         Map<String, Long> countByType,
                         Map<String, Long> countByTag) {
        this.totalCount = totalCount;
        this.countByLevel = countByLevel == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(countByLevel));
        this.countByType = countByType == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(countByType));
        this.countByTag = countByTag == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(countByTag));
    }

    public long getTotalCount() {
        return totalCount;
    }

    /**
     * 获取按级别统计（不可变视图）
     */
    public Map<LogLevel, Long> getCountByLevel() {
        return countByLevel;
    }

    /**
     * 获取按类型统计（不可变视图）
     */
    public Map<String, Long> getCountByType() {
        return countByType;
    }

    /**
     * 获取按标签统计（不可变视图）
     */
    public Map<String, Long> getCountByTag() {
        return countByTag;
    }

    @Override
    public String toString() {
        return "LogStatistics{"
                + "totalCount=" + totalCount
                + ", countByLevel=" + countByLevel
                + ", countByType=" + countByType
                + ", countByTag=" + countByTag
                + '}';
    }
}
