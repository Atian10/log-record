package com.atian10.logrecord.core.engine;

/**
 * flush(long) 的等待结果
 * <p>
 * 描述一次 flush 调用的目标范围与完成情况。目标范围是调用时刻引擎已接收的最大序号及之前的
 * 全部记录；调用之后新接收的记录不属于本次等待边界，不会延长本次等待。
 * </p>
 * <p>
 * <b>语义要点</b>：COMPLETED 只表示目标范围内所有记录均已到达终态（保存、失败、丢弃或未知），
 * 不代表全部保存成功；失败和丢弃同样会结束等待。判断调用前的数据是否完整落盘应使用
 * {@link #isAllPersisted()}。
 * </p>
 * <p>
 * saved/failed/dropped/unknown 统计本次等待期间在目标范围内新到达终态的数量；
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
    /** 等待期间终态已确定、但无法确认保存归属的条目数。 */
    private final long unknown;
    /** 目标范围的累计结果，包含调用之前已终态的条目。 */
    private final long targetSaved, targetFailed, targetDropped, targetUnknown;

    /**
     * 构造等待结果（由引擎在等待结束时创建）
     */
    FlushResult(Outcome outcome, long targetSeq, long alreadyFinalized,
                long saved, long failed, long dropped, long pending) {
        this(outcome, targetSeq, alreadyFinalized, saved, failed, dropped, 0L,
                pending, saved, failed, dropped, alreadyFinalized);
    }

    /** 引擎以同一统计快照构造等待增量与目标累计结果，单位均为记录条数。 */
    FlushResult(Outcome outcome, long targetSeq, long alreadyFinalized,
                long saved, long failed, long dropped, long unknown, long pending,
                long targetSaved, long targetFailed, long targetDropped, long targetUnknown) {
        this.outcome = outcome;
        this.targetSeq = targetSeq;
        this.alreadyFinalized = alreadyFinalized;
        this.saved = saved;
        this.failed = failed;
        this.dropped = dropped;
        this.pending = pending;
        this.unknown = unknown;
        this.targetSaved = targetSaved;
        this.targetFailed = targetFailed;
        this.targetDropped = targetDropped;
        this.targetUnknown = targetUnknown;
    }

    /** 等待期间结果归属未知的条数。 */
    public long getUnknown() { return unknown; }
    /** 整个目标范围已确认保存的条数。 */
    public long getTargetSaved() { return targetSaved; }
    /** 整个目标范围已确认失败的条数。 */
    public long getTargetFailed() { return targetFailed; }
    /** 整个目标范围已丢弃的条数。 */
    public long getTargetDropped() { return targetDropped; }
    /** 整个目标范围结果归属未知的条数。 */
    public long getTargetUnknown() { return targetUnknown; }

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
     * <p>必须确认整个目标范围保存成功，历史失败与未知结果同样阻止成功。</p>
     */
    public boolean isAllPersisted() {
        return outcome == Outcome.COMPLETED && pending == 0L && targetSaved == targetSeq
                && targetFailed == 0L && targetDropped == 0L && targetUnknown == 0L;
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
                + ", unknown=" + unknown
                + ", targetSaved=" + targetSaved
                + ", targetFailed=" + targetFailed
                + ", targetDropped=" + targetDropped
                + ", targetUnknown=" + targetUnknown
                + '}';
    }
}
