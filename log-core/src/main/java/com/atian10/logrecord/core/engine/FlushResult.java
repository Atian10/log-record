package com.atian10.logrecord.core.engine;

/**
 * flush(long) 的等待结果
 * <p>
 * 描述一次 flush 调用的目标范围与完成情况。目标范围是调用时刻引擎已接收的最大序号及之前的
 * 全部记录；调用之后新接收的记录不属于本次等待边界，不会延长本次等待。
 * </p>
 * <p>
 * <b>语义要点</b>：COMPLETED 只表示目标范围内所有记录均已到达终态（保存、失败或丢弃），
 * 不代表全部保存成功；失败和丢弃同样会结束等待。判断调用前的数据是否完整落盘应使用
 * {@link #isAllPersisted()}。
 * </p>
 * <p>
 * saved/failed/dropped 统计的是本次等待期间在目标范围内<b>新</b>到达终态的记录数量；
 * 调用前已完成的数量见 {@link #getAlreadyFinalized()}，两者之和加 pending 等于目标范围总数。
 * 不可变对象。
 * </p>
 */
public final class FlushResult {

    /**
     * 等待结束方式
     */
    public enum Outcome {
        /** 目标范围内所有记录均已到达终态 */
        COMPLETED,
        /** 超时，目标范围内仍有记录未到达终态 */
        TIMEOUT,
        /** 等待线程被中断，目标范围内仍有记录未到达终态 */
        INTERRUPTED
    }

    /** 等待结束方式 */
    private final Outcome outcome;
    /** 本次等待覆盖的最大接收序号（目标上界） */
    private final long targetSeq;
    /** 调用时目标范围内已到达终态的记录数 */
    private final long alreadyFinalized;
    /** 等待期间目标范围内新保存成功的记录数 */
    private final long saved;
    /** 等待期间目标范围内新判定失败的记录数 */
    private final long failed;
    /** 等待期间目标范围内新被丢弃的记录数 */
    private final long dropped;
    /** 返回时目标范围内仍未到达终态的记录数 */
    private final long pending;

    /**
     * 构造等待结果（由引擎在等待结束时创建）
     */
    FlushResult(Outcome outcome, long targetSeq, long alreadyFinalized,
                long saved, long failed, long dropped, long pending) {
        this.outcome = outcome;
        this.targetSeq = targetSeq;
        this.alreadyFinalized = alreadyFinalized;
        this.saved = saved;
        this.failed = failed;
        this.dropped = dropped;
        this.pending = pending;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** 本次等待覆盖的最大接收序号（目标上界），无已接收记录时为 0 */
    public long getTargetSeq() {
        return targetSeq;
    }

    /** 调用时目标范围内已到达终态的记录数 */
    public long getAlreadyFinalized() {
        return alreadyFinalized;
    }

    /** 等待期间目标范围内新保存成功的记录数 */
    public long getSaved() {
        return saved;
    }

    /** 等待期间目标范围内新判定失败的记录数 */
    public long getFailed() {
        return failed;
    }

    /** 等待期间目标范围内新被丢弃的记录数 */
    public long getDropped() {
        return dropped;
    }

    /** 返回时目标范围内仍未到达终态的记录数 */
    public long getPending() {
        return pending;
    }

    /**
     * 目标范围内是否全部完成终态且无失败、无丢弃
     * <p>数据完整性判断入口：COMPLETED 且 failed==0 且 dropped==0</p>
     */
    public boolean isAllPersisted() {
        return outcome == Outcome.COMPLETED && failed == 0L && dropped == 0L;
    }

    @Override
    public String toString() {
        return "FlushResult{"
                + "outcome=" + outcome
                + ", targetSeq=" + targetSeq
                + ", alreadyFinalized=" + alreadyFinalized
                + ", saved=" + saved
                + ", failed=" + failed
                + ", dropped=" + dropped
                + ", pending=" + pending
                + '}';
    }
}
