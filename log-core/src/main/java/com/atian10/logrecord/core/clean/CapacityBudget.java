package com.atian10.logrecord.core.clean;

/**
 * 已解析的全库容量预算
 * <p>
 * 由配置按迁移规则解析得到：预算字节数（1 MB = 1024 × 1024 字节）与
 * 允许参与容量删除的表。禁用清理策略的表始终不参与（受保护）。
 * 不可变对象；解析逻辑见 {@code com.atian10.logrecord.core.config.CapacityBudgets}。
 * </p>
 */
public final class CapacityBudget {

    /** 预算字节数 */
    private final long budgetBytes;
    /** 日志表是否允许参与容量删除 */
    private final boolean logsEligible;
    /** 异常表是否允许参与容量删除 */
    private final boolean exceptionsEligible;

    /**
     * 构造容量预算
     *
     * @param budgetBytes 预算字节数（正数）
     * @param logsEligible 日志表是否允许参与容量删除
     * @param exceptionsEligible 异常表是否允许参与容量删除
     */
    public CapacityBudget(long budgetBytes, boolean logsEligible, boolean exceptionsEligible) {
        if (budgetBytes <= 0L) throw new IllegalArgumentException("budgetBytes must be positive");
        this.budgetBytes = budgetBytes;
        this.logsEligible = logsEligible;
        this.exceptionsEligible = exceptionsEligible;
    }

    /** 预算字节数（正数） */
    public long getBudgetBytes() {
        return budgetBytes;
    }

    /** 日志表是否允许参与容量删除 */
    public boolean isLogsEligible() {
        return logsEligible;
    }

    /** 异常表是否允许参与容量删除 */
    public boolean isExceptionsEligible() {
        return exceptionsEligible;
    }

    @Override
    public String toString() {
        return "CapacityResult{budgetBytes=" + budgetBytes
                + ", logsEligible=" + logsEligible
                + ", exceptionsEligible=" + exceptionsEligible + '}';
    }
}
