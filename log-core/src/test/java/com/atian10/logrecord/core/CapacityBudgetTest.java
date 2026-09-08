package com.atian10.logrecord.core;

import com.atian10.logrecord.core.clean.CapacityBudget;
import com.atian10.logrecord.core.config.CapacityBudgets;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.config.LogConfigUpdater;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 容量预算迁移与冲突校验测试（T-07 / ISSUE-05 配置部分）
 * <p>
 * 覆盖修复方案 B4 迁移规则表：旧值自动映射、相同/冲突、显式禁用、
 * 显式预算一致性要求、负值与溢出拒绝、动态更新失败保持原配置。
 * </p>
 */
public class CapacityBudgetTest {

    private static final long MB = 1024L * 1024L;

    private LogConfig.Builder baseConfig() {
        return LogConfig.builder()
                .storage(new FakeStorage())
                .exceptionStorage(new FakeExceptionStorage());
    }

    @Test
    public void unset_singleLegacyValue_mapsToDatabaseBudget() {
        LogConfig config = baseConfig()
                .cleanPolicy(CleanPolicy.builder().enable(true).keepDays(7).maxDbSizeMB(64).build())
                .exceptionCleanPolicy(CleanPolicy.builder().enable(true).keepDays(30).build())
                .build();
        CapacityBudget budget = CapacityBudgets.resolve(config);
        // 仅日志表有旧正容量值：预算来自它，参与表只有日志表（异常表未配置容量不受牵连）
        assertEquals(64L * MB, budget.getBudgetBytes());
        assertTrue(budget.isLogsEligible());
        assertFalse(budget.isExceptionsEligible());
    }

    @Test
    public void unset_identicalLegacyValues_mapToSharedBudget() {
        LogConfig config = baseConfig()
                .cleanPolicy(policy(true, 32))
                .exceptionCleanPolicy(policy(true, 32))
                .build();
        CapacityBudget budget = CapacityBudgets.resolve(config);
        assertEquals(32L * MB, budget.getBudgetBytes());
        assertTrue(budget.isLogsEligible());
        assertTrue(budget.isExceptionsEligible());
    }

    @Test
    public void unset_differentEnabledLegacyValues_conflictRejected() {
        try {
            baseConfig()
                    .cleanPolicy(policy(true, 32))
                    .exceptionCleanPolicy(policy(true, 64))
                    .build();
            fail("expected IllegalArgumentException for different enabled legacy capacities");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("maxDbSizeMB"));
        }
    }

    @Test
    public void unset_disabledPolicyValue_doesNotConflictAndStaysProtected() {
        // 禁用策略的旧值不参与冲突判定；该表也不参与容量删除
        LogConfig config = baseConfig()
                .cleanPolicy(policy(true, 32))
                .exceptionCleanPolicy(policy(false, 64))
                .build();
        CapacityBudget budget = CapacityBudgets.resolve(config);
        assertEquals(32L * MB, budget.getBudgetBytes());
        assertTrue(budget.isLogsEligible());
        assertFalse("disabled policy table must stay protected", budget.isExceptionsEligible());
    }

    @Test
    public void explicitZero_conflictsWithEnabledLegacyValue() {
        try {
            baseConfig()
                    .cleanPolicy(policy(true, 32))
                    .databaseMaxSizeMB(0L)
                    .build();
            fail("expected IllegalArgumentException for explicit 0 with legacy value");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("0"));
        }
    }

    @Test
    public void explicitZero_withoutLegacyValues_disablesCapacity() {
        LogConfig config = baseConfig()
                .cleanPolicy(CleanPolicy.builder().enable(true).keepDays(7).build())
                .databaseMaxSizeMB(0L)
                .build();
        assertNull(CapacityBudgets.resolve(config));
    }

    @Test
    public void explicitBudget_matchingLegacyValue_accepted() {
        LogConfig config = baseConfig()
                .cleanPolicy(policy(true, 32))
                .exceptionCleanPolicy(CleanPolicy.builder().enable(true).keepDays(3).build())
                .databaseMaxSizeMB(32L)
                .build();
        CapacityBudget budget = CapacityBudgets.resolve(config);
        // 显式预算覆盖全部启用清理策略的表（异常表无旧值也参与）
        assertEquals(32L * MB, budget.getBudgetBytes());
        assertTrue(budget.isLogsEligible());
        assertTrue(budget.isExceptionsEligible());
    }

    @Test
    public void explicitBudget_conflictingLegacyValue_rejected() {
        try {
            baseConfig()
                    .cleanPolicy(policy(true, 32))
                    .databaseMaxSizeMB(64L)
                    .build();
            fail("expected IllegalArgumentException for mismatched explicit budget");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("64"));
        }
    }

    @Test
    public void negativeOrOverflowingValue_rejected() {
        try {
            baseConfig().databaseMaxSizeMB(-1L).build();
            fail("expected IllegalArgumentException for negative value");
        } catch (IllegalArgumentException expected) {
            // 期望异常
        }
        try {
            baseConfig().databaseMaxSizeMB(Long.MAX_VALUE).build();
            fail("expected IllegalArgumentException for overflow");
        } catch (IllegalArgumentException expected) {
            // 期望异常
        }
    }

    @Test
    public void noBudgetAnywhere_resolvesNull() {
        LogConfig config = baseConfig()
                .cleanPolicy(CleanPolicy.builder().enable(true).keepDays(7).maxRecordCount(100).build())
                .exceptionCleanPolicy(CleanPolicy.builder().enable(true).keepDays(7).build())
                .build();
        assertNull(CapacityBudgets.resolve(config));
    }

    @Test
    public void updaterConflict_keepsOriginalConfig() {
        LogConfig original = baseConfig()
                .cleanPolicy(policy(true, 32))
                .build();
        LogConfigUpdater updater = new LogConfigUpdater(original);
        try {
            updater.updateExceptionCleanPolicy(policy(true, 64));
            fail("expected IllegalArgumentException on conflicting update");
        } catch (IllegalArgumentException expected) {
            // 期望异常：CAS 前构建失败，原配置保持
        }
        assertSame(original, updater.get());
        assertNull(updater.get().getExceptionCleanPolicy().getMaxDbSizeMB() == 0L ? null : null);
        assertEquals(0L, updater.get().getExceptionCleanPolicy().getMaxDbSizeMB());
    }

    /**
     * 构建启用/禁用且带旧容量值的策略
     */
    private CleanPolicy policy(boolean enabled, long maxDbSizeMB) {
        return CleanPolicy.builder()
                .enable(enabled)
                .keepDays(enabled ? 7 : 0)
                .maxDbSizeMB(maxDbSizeMB)
                .build();
    }
}
