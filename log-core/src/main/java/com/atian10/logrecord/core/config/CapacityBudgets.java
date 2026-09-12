package com.atian10.logrecord.core.config;

import com.atian10.logrecord.core.clean.CapacityBudget;

/**
 * 容量预算解析工具
 * <p>
 * 按修复方案 B4 的迁移规则从 {@link LogConfig} 解析全库容量预算：
 * </p>
 * <ul>
 *   <li>未设置 databaseMaxSizeMB 且启用策略的旧容量值（maxDbSizeMB &gt; 0）相同（或仅一个有效值）：
 *       自动映射为数据库预算，参与表 = 启用策略且有旧正容量值的表</li>
 *   <li>显式设置 databaseMaxSizeMB：预算来自新值，参与表 = 启用清理策略的全部表；
 *       旧正容量值必须与新值一致，显式 0 与旧正值冲突（冲突在 LogConfig.build() 拦截）</li>
 *   <li>没有有效容量预算：返回 null，不执行容量驱动删除</li>
 * </ul>
 * <p>禁用清理策略的表始终不参与容量删除（受保护）。冲突校验在
 * {@link LogConfig.Builder#build()} 完成；本类假定配置已通过校验，
 * 仅做防御性冲突检查。</p>
 */
public final class CapacityBudgets {

    /** 容量换算：1 MB = 1024 × 1024 字节（沿用既有参数口径） */
    public static final long BYTES_PER_MB = 1024L * 1024L;

    private CapacityBudgets() {
        // 工具类禁止实例化
    }

    /** 新旧 MB 字段共用溢出检查；先校验再相乘，兼容 Android API 21。 */
    static long checkedBytes(long sizeMb, String name) {
        if (sizeMb < 0L || sizeMb > Long.MAX_VALUE / BYTES_PER_MB)
            throw new IllegalArgumentException(name + " is outside supported range: " + sizeMb);
        return sizeMb * BYTES_PER_MB;
    }

    /**
     * 从配置解析容量预算
     *
     * @param config 已通过构建校验的配置，不可为 null
     * @return 预算；无有效预算时返回 null
     * @throws IllegalStateException 防御性冲突检查失败（正常流程不会出现）
     */
    public static CapacityBudget resolve(LogConfig config) {
        if (config == null) {
            throw new NullPointerException("config == null");
        }
        Long newSizeMb = config.getDatabaseMaxSizeMB();
        CleanPolicy logPolicy = config.getCleanPolicy();
        CleanPolicy expPolicy = config.getExceptionCleanPolicy();
        long logOld = positiveOldCapacity(logPolicy);
        long expOld = positiveOldCapacity(expPolicy);

        if (newSizeMb != null) {
            if (newSizeMb == 0L) {
                // 显式禁用：启用策略存在旧正值即冲突（build 已拦截，此处防御）
                if (logOld > 0L || expOld > 0L) {
                    throw new IllegalStateException(
                            "databaseMaxSizeMB=0 conflicts with legacy maxDbSizeMB in enabled policies");
                }
                return null;
            }
            if ((logOld > 0L && logOld != newSizeMb) || (expOld > 0L && expOld != newSizeMb)) {
                throw new IllegalStateException(
                        "databaseMaxSizeMB conflicts with legacy maxDbSizeMB in enabled policies");
            }
            // 显式预算：覆盖全部启用清理策略的表；禁用策略的表受保护
            return new CapacityBudget(checkedBytes(newSizeMb, "databaseMaxSizeMB"),
                    isEnabled(logPolicy), isEnabled(expPolicy));
        }

        // 未设置新字段：旧正容量值自动映射
        if (logOld == 0L && expOld == 0L) {
            return null;
        }
        if (logOld > 0L && expOld > 0L && logOld != expOld) {
            throw new IllegalStateException(
                    "enabled policies declare different legacy maxDbSizeMB values: "
                            + logOld + " vs " + expOld);
        }
        long mb = Math.max(logOld, expOld);
        return new CapacityBudget(checkedBytes(mb, "maxDbSizeMB"), logOld > 0L, expOld > 0L);
    }

    /**
     * 启用策略中的旧正容量值（maxDbSizeMB），未启用或未配置返回 0
     */
    private static long positiveOldCapacity(CleanPolicy policy) {
        if (policy == null || !policy.isEnabled() || policy.getMaxDbSizeMB() <= 0L) {
            return 0L;
        }
        return policy.getMaxDbSizeMB();
    }

    /**
     * 策略是否启用
     */
    private static boolean isEnabled(CleanPolicy policy) {
        return policy != null && policy.isEnabled();
    }
}
