package com.atian10.logrecord.core.config;

import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.ILogFilter;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.clean.CapacityCoordinator;
import com.atian10.logrecord.core.engine.QueueFullPolicy;
import com.atian10.logrecord.core.export.ExportEncoding;

/**
 * 日志库全局配置
 * <p>
 * 不可变对象，使用 Builder 模式构建。构造后字段只读。
 * 运行时动态修改通过 {@code LogConfigUpdater} 以 volatile 引用整体替换实现并发可见性，
 * 本类不做字段级 volatile（保持纯不可变）。
 * </p>
 * <p>
 * 配置分两类：
 * <ul>
 *   <li>静态配置：storage/exceptionStorage/queueCapacity/queueFullPolicy/batchSize/batchIntervalMillis，
 *       初始化后建议不修改（修改需重新初始化引擎）</li>
 *   <li>动态配置：consoleEnabled/captureMethodLine/versionTag/formatter/logFilter/cleanPolicy/
 *       exceptionCleanPolicy/exportEncoding，可通过 {@code LogConfigUpdater} 运行时修改</li>
 * </ul>
 * </p>
 */
public final class LogConfig {

    // ===== 静态配置（初始化后建议不修改） =====

    /** 日志存储实现（平台适配层提供，必填） */
    private final IStorage storage;
    /** 异常存储实现（平台适配层提供，必填） */
    private final IExceptionStorage exceptionStorage;
    /** 异步队列容量 */
    private final int queueCapacity;
    /** 队列满策略 */
    private final QueueFullPolicy queueFullPolicy;
    /** 批量写入阈值（达到此数量触发落盘） */
    private final int batchSize;
    /** 批量写入周期（毫秒，达到此时长触发落盘） */
    private final long batchIntervalMillis;
    /** 全库容量协调器（平台适配层提供；null 表示无容量清理能力） */
    private final CapacityCoordinator capacityCoordinator;

    // ===== 动态配置（可运行时修改） =====

    /** 是否输出到控制台（默认 false） */
    private final boolean consoleEnabled;
    /** 是否捕获调用方法名/行号（默认 false） */
    private final boolean captureMethodLine;
    /** 版本标签（语义由业务方自定义，可空） */
    private final String versionTag;
    /** 格式化器（导出时使用，null 表示使用 DefaultFormatter） */
    private final IFormatter formatter;
    /** 日志过滤器（null 表示不过滤） */
    private final ILogFilter logFilter;
    /** 日志清理策略 */
    private final CleanPolicy cleanPolicy;
    /** 异常清理策略（独立于日志清理） */
    private final CleanPolicy exceptionCleanPolicy;
    /**
     * 数据库级容量上限（MB）
     * <p>null 表示未设置（按迁移规则从旧 maxDbSizeMB 推导）；0 表示显式禁用容量限制；
     * 正数为全库预算。换算口径 1 MB = 1024 × 1024 字节</p>
     */
    private final Long databaseMaxSizeMB;
    /** 导出编码（默认 UTF_8） */
    private final ExportEncoding exportEncoding;

    private LogConfig(Builder builder) {
        if (builder.storage == null) {
            throw new IllegalStateException("storage must be set");
        }
        if (builder.exceptionStorage == null) {
            throw new IllegalStateException("exceptionStorage must be set");
        }
        this.storage = builder.storage;
        this.exceptionStorage = builder.exceptionStorage;
        this.queueCapacity = builder.queueCapacity;
        this.queueFullPolicy = builder.queueFullPolicy;
        this.batchSize = builder.batchSize;
        this.batchIntervalMillis = builder.batchIntervalMillis;
        this.capacityCoordinator = builder.capacityCoordinator;
        this.consoleEnabled = builder.consoleEnabled;
        this.captureMethodLine = builder.captureMethodLine;
        this.versionTag = builder.versionTag;
        this.formatter = builder.formatter;
        this.logFilter = builder.logFilter;
        this.cleanPolicy = builder.cleanPolicy;
        this.exceptionCleanPolicy = builder.exceptionCleanPolicy;
        this.databaseMaxSizeMB = builder.databaseMaxSizeMB;
        this.exportEncoding = builder.exportEncoding;
        // 容量预算校验：负值/溢出/新旧冲突统一在此拦截（初始化与动态更新的 build 都经过这里）
        verifyCapacityConfig(builder);
    }

    public IStorage getStorage() {
        return storage;
    }

    public IExceptionStorage getExceptionStorage() {
        return exceptionStorage;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public QueueFullPolicy getQueueFullPolicy() {
        return queueFullPolicy;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public long getBatchIntervalMillis() {
        return batchIntervalMillis;
    }

    /** 全库容量协调器（平台适配层提供；null 表示无容量清理能力） */
    public CapacityCoordinator getCapacityCoordinator() {
        return capacityCoordinator;
    }

    public boolean isConsoleEnabled() {
        return consoleEnabled;
    }

    public boolean isCaptureMethodLine() {
        return captureMethodLine;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public IFormatter getFormatter() {
        return formatter;
    }

    public ILogFilter getLogFilter() {
        return logFilter;
    }

    public CleanPolicy getCleanPolicy() {
        return cleanPolicy;
    }

    public CleanPolicy getExceptionCleanPolicy() {
        return exceptionCleanPolicy;
    }

    /**
     * 数据库级容量上限（MB）
     * @return null 表示未设置；0 表示显式禁用容量限制；正数为全库预算
     */
    public Long getDatabaseMaxSizeMB() {
        return databaseMaxSizeMB;
    }

    public ExportEncoding getExportEncoding() {
        return exportEncoding;
    }

    /**
     * 创建配置构建器
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 基于已有配置创建构建器（用于运行时修改时拷贝）
     * @param source 源配置
     * @return 复制了源配置字段的 Builder
     */
    public static Builder builderFrom(LogConfig source) {
        Builder b = new Builder();
        b.storage = source.storage;
        b.exceptionStorage = source.exceptionStorage;
        b.queueCapacity = source.queueCapacity;
        b.queueFullPolicy = source.queueFullPolicy;
        b.batchSize = source.batchSize;
        b.batchIntervalMillis = source.batchIntervalMillis;
        b.capacityCoordinator = source.capacityCoordinator;
        b.consoleEnabled = source.consoleEnabled;
        b.captureMethodLine = source.captureMethodLine;
        b.versionTag = source.versionTag;
        b.formatter = source.formatter;
        b.logFilter = source.logFilter;
        b.cleanPolicy = source.cleanPolicy;
        b.exceptionCleanPolicy = source.exceptionCleanPolicy;
        b.databaseMaxSizeMB = source.databaseMaxSizeMB;
        b.exportEncoding = source.exportEncoding;
        return b;
    }

    /**
     * 容量预算校验（B4 迁移规则）
     * <ul>
     *   <li>databaseMaxSizeMB 负值或换算溢出：拒绝</li>
     *   <li>显式 0（禁用）与启用策略中的旧正容量值：冲突</li>
     *   <li>显式正数与启用策略中的旧正容量值不一致：冲突（需一致或清除旧值）</li>
     *   <li>未设置且启用策略的旧正容量值不同：冲突（不擅自取小或相加）</li>
     * </ul>
     *
     * @throws IllegalArgumentException 违反上述规则时
     */
    private static void verifyCapacityConfig(Builder builder) {
        Long newSizeMb = builder.databaseMaxSizeMB;
        if (newSizeMb == null) {
            long logOld = positiveOldCapacity(builder.cleanPolicy);
            long expOld = positiveOldCapacity(builder.exceptionCleanPolicy);
            if (logOld > 0L && expOld > 0L && logOld != expOld) {
                throw new IllegalArgumentException(
                        "enabled cleanPolicies declare different maxDbSizeMB values ("
                                + logOld + " vs " + expOld
                                + "); align them or set databaseMaxSizeMB explicitly");
            }
            return;
        }
        if (newSizeMb < 0L) {
            throw new IllegalArgumentException("databaseMaxSizeMB must be >= 0 (0 = disable)");
        }
        if (newSizeMb != 0L && newSizeMb > Long.MAX_VALUE / CapacityBudgets.BYTES_PER_MB) {
            throw new IllegalArgumentException("databaseMaxSizeMB overflows byte budget: " + newSizeMb);
        }
        long logOld = positiveOldCapacity(builder.cleanPolicy);
        long expOld = positiveOldCapacity(builder.exceptionCleanPolicy);
        if (newSizeMb == 0L) {
            if (logOld > 0L || expOld > 0L) {
                throw new IllegalArgumentException(
                        "databaseMaxSizeMB=0 (disable) conflicts with legacy maxDbSizeMB "
                                + "in enabled policies; remove the legacy values first");
            }
            return;
        }
        if ((logOld > 0L && logOld != newSizeMb) || (expOld > 0L && expOld != newSizeMb)) {
            throw new IllegalArgumentException(
                    "databaseMaxSizeMB=" + newSizeMb
                            + " conflicts with legacy maxDbSizeMB in enabled policies (log="
                            + logOld + ", exception=" + expOld
                            + "); align or clear the legacy values");
        }
    }

    /**
     * 启用策略中的旧正容量值，未启用或未配置返回 0
     */
    private static long positiveOldCapacity(CleanPolicy policy) {
        if (policy == null || !policy.isEnabled() || policy.getMaxDbSizeMB() <= 0L) {
            return 0L;
        }
        return policy.getMaxDbSizeMB();
    }

    /**
     * 配置构建器
     * <p>
     * 默认值：consoleEnabled=false，captureMethodLine=false，versionTag=null，
     * queueCapacity=4096，queueFullPolicy=DROP_OLDEST，batchSize=100，
     * batchIntervalMillis=1000，exportEncoding=UTF_8，
     * cleanPolicy/exceptionCleanPolicy=禁用，formatter/logFilter=null
     * </p>
     */
    public static final class Builder {

        private IStorage storage;
        private IExceptionStorage exceptionStorage;
        private int queueCapacity = 4096;
        private QueueFullPolicy queueFullPolicy = QueueFullPolicy.DROP_OLDEST;
        private int batchSize = 100;
        private long batchIntervalMillis = 1000L;
        private CapacityCoordinator capacityCoordinator;
        private boolean consoleEnabled = false;
        private boolean captureMethodLine = false;
        private String versionTag;
        private IFormatter formatter;
        private ILogFilter logFilter;
        private CleanPolicy cleanPolicy = CleanPolicy.builder().build();
        private CleanPolicy exceptionCleanPolicy = CleanPolicy.builder().build();
        /** 数据库级容量上限（MB）；null=未设置，0=显式禁用 */
        private Long databaseMaxSizeMB;
        private ExportEncoding exportEncoding = ExportEncoding.UTF_8;

        public Builder storage(IStorage storage) {
            this.storage = storage;
            return this;
        }

        public Builder exceptionStorage(IExceptionStorage exceptionStorage) {
            this.exceptionStorage = exceptionStorage;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder queueFullPolicy(QueueFullPolicy queueFullPolicy) {
            this.queueFullPolicy = queueFullPolicy;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder batchIntervalMillis(long batchIntervalMillis) {
            this.batchIntervalMillis = batchIntervalMillis;
            return this;
        }

        /**
         * 设置全库容量协调器（平台适配层提供）
         * <p>静态配置：初始化后不可通过 set() 替换。null（默认）表示无容量清理能力，
         * 存在有效预算时容量阶段将记录"缺少容量协调器"并跳过</p>
         */
        public Builder capacityCoordinator(CapacityCoordinator capacityCoordinator) {
            this.capacityCoordinator = capacityCoordinator;
            return this;
        }

        public Builder consoleEnabled(boolean consoleEnabled) {
            this.consoleEnabled = consoleEnabled;
            return this;
        }

        public Builder captureMethodLine(boolean captureMethodLine) {
            this.captureMethodLine = captureMethodLine;
            return this;
        }

        public Builder versionTag(String versionTag) {
            this.versionTag = versionTag;
            return this;
        }

        public Builder formatter(IFormatter formatter) {
            this.formatter = formatter;
            return this;
        }

        public Builder logFilter(ILogFilter logFilter) {
            this.logFilter = logFilter;
            return this;
        }

        public Builder cleanPolicy(CleanPolicy cleanPolicy) {
            this.cleanPolicy = cleanPolicy == null ? CleanPolicy.builder().build() : cleanPolicy;
            return this;
        }

        public Builder exceptionCleanPolicy(CleanPolicy exceptionCleanPolicy) {
            this.exceptionCleanPolicy = exceptionCleanPolicy == null
                    ? CleanPolicy.builder().build() : exceptionCleanPolicy;
            return this;
        }

        /**
         * 设置数据库级容量上限（MB）
         * <p>null 表示未设置（按迁移规则从启用策略的旧 maxDbSizeMB 推导）；
         * 0 表示显式禁用容量限制；正数为全库预算。负值与换算溢出在 build() 拒绝</p>
         */
        public Builder databaseMaxSizeMB(Long databaseMaxSizeMB) {
            this.databaseMaxSizeMB = databaseMaxSizeMB;
            return this;
        }

        public Builder exportEncoding(ExportEncoding exportEncoding) {
            this.exportEncoding = exportEncoding == null ? ExportEncoding.UTF_8 : exportEncoding;
            return this;
        }

        /**
         * 构建配置
         * @return 不可变配置对象
         * @throws IllegalStateException 当 storage 或 exceptionStorage 未设置时
         */
        public LogConfig build() {
            return new LogConfig(this);
        }
    }
}
