package com.atian10.logrecord.core.clean;

/**
 * 全库容量协调器
 * <p>
 * 由数据库所有者（平台适配层）提供实现：在导出快照复制互斥的保护下，
 * 按预算删除允许参与表中最旧的记录并回收磁盘空间，返回
 * {@link CleanResult}。一个数据库只应有一个协调器实例，由
 * {@code LogConfig#capacityCoordinator} 注入。
 * </p>
 */
public interface CapacityCoordinator {

    /**
     * 执行一轮全库容量维护
     * <p>
     * 导出快照复制期间应返回 {@link CleanResult.Outcome#POSTPONED} 而不是阻塞等待。
     * 删除资格严格按 {@code budget} 的表参与标记执行；禁用策略的表受保护。
     * </p>
     *
     * @param budget 已解析的容量预算（非 null）
     * @return 维护结果；不抛异常，失败以 FAILED 结局返回
     */
    CleanResult enforceCapacity(CapacityBudget budget);
}
