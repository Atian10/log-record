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
 * batchIntervalMillis）初始化后不应修改，本类不提供对应方法。
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
     * @param newConfig 新配置，不可为 null
     */
    public void set(LogConfig newConfig) {
        if (newConfig == null) {
            throw new NullPointerException("newConfig == null");
        }
        configRef.set(newConfig);
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
