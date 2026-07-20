package com.atian10.logrecord.core.config;

import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.ILogFilter;
import com.atian10.logrecord.core.export.ExportEncoding;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 日志配置更新器
 * <p>
 * 持有 {@link AtomicReference}<{@link LogConfig}>，提供线程安全的运行时配置修改。
 * 通过 CAS 整体替换不可变 LogConfig 实现并发可见性，避免字段级 volatile 的中间态问题。
 * </p>
 * <p>
 * 仅支持修改动态配置项（consoleEnabled/captureMethodLine/versionTag/formatter/logFilter/
 * cleanPolicy/exceptionCleanPolicy/exportEncoding）。
 * 静态配置项（storage/exceptionStorage/queueCapacity/queueFullPolicy/batchSize/
 * batchIntervalMillis）初始化后不应修改；若通过 {@link #set(LogConfig)} 整体替换时
 * 检测到静态配置变更，将抛 {@link IllegalArgumentException}（需重新 init LogManager）。
 * </p>
 */
public final class LogConfigUpdater {

    private final AtomicReference<LogConfig> configRef;

    /**
     * 构造更新器
     * @param initialConfig 初始配置，不可为 null
     */
    public LogConfigUpdater(LogConfig initialConfig) {
        if (initialConfig == null) {
            throw new NullPointerException("initialConfig == null");
        }
        this.configRef = new AtomicReference<>(initialConfig);
    }

    /**
     * 获取当前配置
     * @return 当前生效的配置
     */
    public LogConfig get() {
        return configRef.get();
    }

    /**
     * 整体替换配置
     * <p>
     * 仅允许修改动态配置项（consoleEnabled/captureMethodLine/versionTag/formatter/logFilter/
     * cleanPolicy/exceptionCleanPolicy/exportEncoding）。
     * 静态配置项（storage/exceptionStorage/queueCapacity/queueFullPolicy/batchSize/
     * batchIntervalMillis）必须与当前配置一致，否则抛 {@link IllegalArgumentException}。
     * 这是因为静态配置的变更需要重新初始化引擎（如重建队列、重启 worker），
     * 通过 set() 静默替换不会触发引擎重建，会导致配置与实际行为不一致。
     * </p>
     * @param newConfig 新配置，不可为 null
     * @throws IllegalArgumentException 当静态配置项与当前配置不一致时
     */
    public void set(LogConfig newConfig) {
        if (newConfig == null) {
            throw new NullPointerException("newConfig == null");
        }
        LogConfig current = configRef.get();
        verifyStaticConfigUnchanged(current, newConfig);
        configRef.set(newConfig);
    }

    /**
     * 校验静态配置项不可变
     * <p>检查 6 项静态配置（storage/exceptionStorage/queueCapacity/queueFullPolicy/
     * batchSize/batchIntervalMillis）是否与当前配置一致，不一致抛异常</p>
     * @param current 当前生效配置
     * @param candidate 待替换配置
     * @throws IllegalArgumentException 当任一静态配置项不一致时
     */
    private void verifyStaticConfigUnchanged(LogConfig current, LogConfig candidate) {
        if (current.getStorage() != candidate.getStorage()) {
            throw new IllegalArgumentException(
                    "storage cannot be changed via set(); re-init LogManager instead");
        }
        if (current.getExceptionStorage() != candidate.getExceptionStorage()) {
            throw new IllegalArgumentException(
                    "exceptionStorage cannot be changed via set(); re-init LogManager instead");
        }
        if (current.getQueueCapacity() != candidate.getQueueCapacity()) {
            throw new IllegalArgumentException(
                    "queueCapacity cannot be changed via set(); re-init LogManager instead");
        }
        if (current.getQueueFullPolicy() != candidate.getQueueFullPolicy()) {
            throw new IllegalArgumentException(
                    "queueFullPolicy cannot be changed via set(); re-init LogManager instead");
        }
        if (current.getBatchSize() != candidate.getBatchSize()) {
            throw new IllegalArgumentException(
                    "batchSize cannot be changed via set(); re-init LogManager instead");
        }
        if (current.getBatchIntervalMillis() != candidate.getBatchIntervalMillis()) {
            throw new IllegalArgumentException(
                    "batchIntervalMillis cannot be changed via set(); re-init LogManager instead");
        }
    }

    /**
     * 修改是否输出到控制台
     */
    public void updateConsoleEnabled(boolean consoleEnabled) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .consoleEnabled(consoleEnabled)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }

    /**
     * 修改是否捕获方法名/行号
     */
    public void updateCaptureMethodLine(boolean captureMethodLine) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .captureMethodLine(captureMethodLine)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }

    /**
     * 修改版本标签
     */
    public void updateVersionTag(String versionTag) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .versionTag(versionTag)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }

    /**
     * 修改格式化器
     */
    public void updateFormatter(IFormatter formatter) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .formatter(formatter)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }

    /**
     * 修改日志过滤器
     */
    public void updateLogFilter(ILogFilter logFilter) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .logFilter(logFilter)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }

    /**
     * 修改日志清理策略
     */
    public void updateCleanPolicy(CleanPolicy cleanPolicy) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .cleanPolicy(cleanPolicy)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }

    /**
     * 修改异常清理策略
     */
    public void updateExceptionCleanPolicy(CleanPolicy exceptionCleanPolicy) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .exceptionCleanPolicy(exceptionCleanPolicy)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }

    /**
     * 修改导出编码
     */
    public void updateExportEncoding(ExportEncoding exportEncoding) {
        LogConfig current;
        LogConfig updated;
        do {
            current = configRef.get();
            updated = LogConfig.builderFrom(current)
                    .exportEncoding(exportEncoding)
                    .build();
        } while (!configRef.compareAndSet(current, updated));
    }
}
