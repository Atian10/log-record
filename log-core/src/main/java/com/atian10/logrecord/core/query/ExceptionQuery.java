package com.atian10.logrecord.core.query;

/**
 * 异常查询条件
 * <p>
 * 使用 Builder 模式构建，所有条件可选，组合查询。
 * 异常表与日志表独立，查询条件独立。
 * </p>
 */
public final class ExceptionQuery {

    /** 弱关联 tag（按 log_tag 精确匹配） */
    private final String tag;

    /** 异常类名（按 exception_class 精确匹配） */
    private final String exceptionClass;

    /** 关键字（对 exception_message 模糊匹配） */
    private final String keyword;

    /** 起始时间（毫秒，包含） */
    private final Long fromTime;

    /** 结束时间（毫秒，包含） */
    private final Long toTime;

    /** 排序方式 */
    private final OrderBy orderBy;

    /** 分页偏移量（默认 0） */
    private final int offset;

    /** 分页限制条数（0 表示不限） */
    private final int limit;

    private ExceptionQuery(Builder builder) {
        this.tag = builder.tag;
        this.exceptionClass = builder.exceptionClass;
        this.keyword = builder.keyword;
        this.fromTime = builder.fromTime;
        this.toTime = builder.toTime;
        this.orderBy = builder.orderBy;
        this.offset = builder.offset;
        this.limit = builder.limit;
    }

    public String getTag() {
        return tag;
    }

    public String getExceptionClass() {
        return exceptionClass;
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
     * 异常查询条件构建器
     */
    public static final class Builder {

        private String tag;
        private String exceptionClass;
        private String keyword;
        private Long fromTime;
        private Long toTime;
        private OrderBy orderBy = OrderBy.DESC;
        private int offset = 0;
        private int limit = 0;

        public Builder tag(String tag) {
            this.tag = tag;
            return this;
        }

        public Builder exceptionClass(String exceptionClass) {
            this.exceptionClass = exceptionClass;
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

        public ExceptionQuery build() {
            return new ExceptionQuery(this);
        }
    }
}
