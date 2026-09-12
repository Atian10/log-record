package com.atian10.logrecord.core.config;

/**
 * 日志清理策略配置
 * <p>
 * 不可变对象，使用 Builder 模式构建。
 * 支持按天数、按总容量、按记录数三维度组合，任一触发即清理。
 * 异常表独立策略通过 LogConfig.exceptionCleanPolicy 单独配置，本类不内嵌。
 * </p>
 */
public final class CleanPolicy {

    /** 是否开启清理 */
    private final boolean enabled;

    /** 保留天数（0 = 不按天清理） */
    private final int keepDays;

    /** 数据库大小上限 MB（0 = 不限） */
    private final long maxDbSizeMB;

    /** 记录数上限（0 = 不限） */
    private final long maxRecordCount;

    /** 清理周期（小时） */
    private final int cleanIntervalHours;

    /** 构造前校验旧容量字段，包括暂时禁用的策略，防止后续启用溢出预算。 */
    private CleanPolicy(Builder builder) {
        CapacityBudgets.checkedBytes(builder.maxDbSizeMB, "maxDbSizeMB");
        this.enabled = builder.enabled;
        this.keepDays = builder.keepDays;
        this.maxDbSizeMB = builder.maxDbSizeMB;
        this.maxRecordCount = builder.maxRecordCount;
        this.cleanIntervalHours = builder.cleanIntervalHours;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getKeepDays() {
        return keepDays;
    }

    public long getMaxDbSizeMB() {
        return maxDbSizeMB;
    }

    public long getMaxRecordCount() {
        return maxRecordCount;
    }

    public int getCleanIntervalHours() {
        return cleanIntervalHours;
    }

    /**
     * 创建清理策略构建器
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 清理策略构建器
     * <p>
     * 默认值：enabled=false，keepDays=0，maxDbSizeMB=0，maxRecordCount=0，cleanIntervalHours=24
     * </p>
     */
    public static final class Builder {

        private boolean enabled = false;
        private int keepDays = 0;
        private long maxDbSizeMB = 0;
        private long maxRecordCount = 0;
        private int cleanIntervalHours = 24;

        public Builder enable(boolean enable) {
            this.enabled = enable;
            return this;
        }

        public Builder keepDays(int days) {
            this.keepDays = days;
            return this;
        }

        public Builder maxDbSizeMB(long sizeMB) {
            this.maxDbSizeMB = sizeMB;
            return this;
        }

        public Builder maxRecordCount(long count) {
            this.maxRecordCount = count;
            return this;
        }

        public Builder cleanIntervalHours(int hours) {
            this.cleanIntervalHours = hours;
            return this;
        }

        public CleanPolicy build() {
            return new CleanPolicy(this);
        }
    }
}
