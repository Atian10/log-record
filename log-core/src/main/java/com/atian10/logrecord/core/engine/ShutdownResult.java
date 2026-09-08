package com.atian10.logrecord.core.engine;

/**
 * 带超时关闭的等待结果
 * <p>
 * 描述关闭流程在超时时限内的完成情况。COMPLETED 表示接收记录已全部排空且工作线程退出；
 * TIMEOUT 表示仍有工作在后台进行，未完成部分由后台继续收尾，调用方不应立即释放底层
 * 数据库资源。不可变对象。
 * </p>
 */
public final class ShutdownResult {

    /**
     * 关闭等待结束方式
     */
    public enum Outcome {
        /** 工作线程已在超时前退出，接收记录已全部排空 */
        COMPLETED,
        /** 超时，工作线程仍在后台排空或清理线程未退出 */
        TIMEOUT
    }

    /** 关闭等待结束方式 */
    private final Outcome outcome;
    /** 返回时仍未到达终态的接收记录总数 */
    private final long pendingCount;
    /** 清理线程是否已在超时前退出（引擎级结果恒为 true） */
    private final boolean cleanerTerminated;

    private ShutdownResult(Outcome outcome, long pendingCount, boolean cleanerTerminated) {
        this.outcome = outcome;
        this.pendingCount = pendingCount;
        this.cleanerTerminated = cleanerTerminated;
    }

    /**
     * 构造已完成的结果
     *
     * @param pendingCount 仍未终态的接收记录数（正常为 0）
     * @param cleanerTerminated 清理线程是否已退出
     */
    public static ShutdownResult completed(long pendingCount, boolean cleanerTerminated) {
        return new ShutdownResult(Outcome.COMPLETED, pendingCount, cleanerTerminated);
    }

    /**
     * 构造超时的结果
     *
     * @param pendingCount 仍未终态的接收记录数
     * @param cleanerTerminated 清理线程是否已退出
     */
    public static ShutdownResult timeout(long pendingCount, boolean cleanerTerminated) {
        return new ShutdownResult(Outcome.TIMEOUT, pendingCount, cleanerTerminated);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** 返回时仍未到达终态的接收记录总数 */
    public long getPendingCount() {
        return pendingCount;
    }

    /** 清理线程是否已在超时前退出（引擎级结果恒为 true） */
    public boolean isCleanerTerminated() {
        return cleanerTerminated;
    }

    /**
     * 关闭流程是否已完全结束（工作线程与清理线程均退出）
     * <p>为 false 时底层资源仍被后台使用，不应立即关闭数据库连接</p>
     */
    public boolean isFullyTerminated() {
        return outcome == Outcome.COMPLETED && cleanerTerminated;
    }

    @Override
    public String toString() {
        return "ShutdownResult{"
                + "outcome=" + outcome
                + ", pendingCount=" + pendingCount
                + ", cleanerTerminated=" + cleanerTerminated
                + '}';
    }
}
