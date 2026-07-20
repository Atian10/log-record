package com.atian10.logrecord.core.query;

import com.atian10.logrecord.core.model.LogLevel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 日志查询条件
 * <p>
 * 使用 Builder 模式构建，所有条件可选，组合查询。
 * 未设置的条件不参与筛选。
 * </p>
 */
public final class LogQuery {

    /** 日志级别（按级别精确匹配） */
    private final LogLevel level;

    /** 日志类型（按类型精确匹配） */
    private final String type;

    /** 标签（按 Tag 精确匹配） */
    private final String tag;

    /** 关键字（对 message 模糊匹配） */
    private final String keyword;

    /** 起始时间（毫秒，包含） */
    private final Long fromTime;

    /** 结束时间（毫秒，包含） */
    private final Long toTime;

    /** 用户键值对查询条件（精确匹配 key-value） */
    private final Map<String, String> userFields;

    /** 版本标签（按 version_tag 精确匹配） */
    private final String versionTag;

    /** 排序方式 */
    private final OrderBy orderBy;

    /** 分页偏移量（默认 0） */
    private final int offset;

    /** 分页限制条数（0 表示不限） */
    private final int limit;

    private LogQuery(Builder builder) {
        this.level = builder.level;
        this.type = builder.type;
        this.tag = builder.tag;
        this.keyword = builder.keyword;
        this.fromTime = builder.fromTime;
        this.toTime = builder.toTime;
        this.userFields = builder.userFields == null
                ? null
                : Collections.unmodifiableMap(new HashMap<>(builder.userFields));
        this.versionTag = builder.versionTag;
        this.orderBy = builder.orderBy;
        this.offset = builder.offset;
        this.limit = builder.limit;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getType() {
        return type;
    }

    public String getTag() {
        return tag;
    }

    public String getKeyword() {
        return keyword;
    }

    public Long getFromTime() {
        return fromTime;
    }

    public Long getToTime() {
        return toTime;
    }

    /**
     * 获取用户键值对查询条件
     * @return 不可变 Map，无条件时返回 null
     */
    public Map<String, String> getUserFields() {
        return userFields;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public OrderBy getOrderBy() {
        return orderBy;
    }

    public int getOffset() {
        return offset;
    }

    public int getLimit() {
        return limit;
    }

    /**
     * 创建查询条件构建器
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 日志查询条件构建器
     */
    public static final class Builder {

        private LogLevel level;
        private String type;
        private String tag;
        private String keyword;
        private Long fromTime;
        private Long toTime;
        private Map<String, String> userFields;
        private String versionTag;
        private OrderBy orderBy = OrderBy.DESC;
        private int offset = 0;
        private int limit = 0;

        public Builder level(LogLevel level) {
            this.level = level;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder tag(String tag) {
            this.tag = tag;
            return this;
        }

        public Builder keyword(String keyword) {
            this.keyword = keyword;
            return this;
        }

        public Builder fromTime(Long fromTime) {
            this.fromTime = fromTime;
            return this;
        }

        public Builder toTime(Long toTime) {
            this.toTime = toTime;
            return this;
        }

        /**
         * 添加用户键值对查询条件
         * @param key 字段名
         * @param value 字段值
         * @return 当前 Builder
         */
        public Builder userField(String key, String value) {
            if (this.userFields == null) {
                this.userFields = new HashMap<>();
            }
            this.userFields.put(key, value);
            return this;
        }

        public Builder versionTag(String versionTag) {
            this.versionTag = versionTag;
            return this;
        }

        /**
         * 设置排序方式
         * @param orderBy 排序枚举；传 null 时保持默认值 {@link OrderBy#DESC}（不覆盖）
         * @return 当前 Builder
         */
        public Builder orderBy(OrderBy orderBy) {
            if (orderBy != null) {
                this.orderBy = orderBy;
            }
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public LogQuery build() {
            return new LogQuery(this);
        }
    }
}
