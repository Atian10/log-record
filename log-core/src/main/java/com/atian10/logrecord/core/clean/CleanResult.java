package com.atian10.logrecord.core.clean;

/**
 * 全库容量清理结果
 * <p>
 * 描述一次容量维护的删除量、度量与结局。MET 才表示达标；
 * NOT_MET/POSTPONED/FAILED 均不能当作"容量清理成功"上报。
 * 不可变对象。
 * </p>
 */
public final class CleanResult {

    /**
     * 容量维护结局
     */
    public enum Outcome {
        /** 结束时数据库相关文件总长不超过预算 */
        MET,
        /** 无法达标（无可删数据/受保护表/空间不足/无进展/工作预算耗尽） */
        NOT_MET,
        /** 推迟维护（如导出快照复制期间） */
        POSTPONED,
        /** 维护执行失败（数据库错误、回收失败等） */
        FAILED
    }

    /** 容量阶段删除的日志条数 */
    private final int deletedLogs;
    /** 容量阶段删除的异常条数 */
    private final int deletedExceptions;
    /** 维护开始时数据库相关文件总长（字节）；-1 表示尚无可靠度量。 */
    private final long bytesBefore;
    /** 维护结束时数据库相关文件总长（字节）；-1 表示未度量或维护异常后未知。 */
    private final long bytesAfter;
    /** 预算字节数 */
    private final long targetBytes;
    /** 结局 */
    private final Outcome outcome;
    /** 停止原因（诊断信息；MET 时可为 null） */
    private final String stopReason;

    /**
     * 构造容量清理结果
     *
     * @param deletedLogs 删除的日志条数
     * @param deletedExceptions 删除的异常条数
     * @param bytesBefore 维护前总长
     * @param bytesAfter 维护后总长
     * @param targetBytes 预算字节数
     * @param outcome 结局
     * @param stopReason 停止原因
     */
    public CleanResult(int deletedLogs, int deletedExceptions, long bytesBefore,
                       long bytesAfter, long targetBytes, Outcome outcome, String stopReason) {
        this.deletedLogs = deletedLogs;
        this.deletedExceptions = deletedExceptions;
        this.bytesBefore = bytesBefore;
        this.bytesAfter = bytesAfter;
        this.targetBytes = targetBytes;
        this.outcome = outcome;
        this.stopReason = stopReason;
    }

    public int getDeletedLogs() {
        return deletedLogs;
    }

    public int getDeletedExceptions() {
        return deletedExceptions;
    }

    public long getBytesBefore() {
        return bytesBefore;
    }

    public long getBytesAfter() {
        return bytesAfter;
    }

    public long getTargetBytes() {
        return targetBytes;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getStopReason() {
        return stopReason;
    }

    @Override
    public String toString() {
        return "CleanResult{"
                + "outcome=" + outcome
                + ", deletedLogs=" + deletedLogs
                + ", deletedExceptions=" + deletedExceptions
                + ", bytesBefore=" + bytesBefore
                + ", bytesAfter=" + bytesAfter
                + ", targetBytes=" + targetBytes
                + ", stopReason='" + stopReason + '\''
                + '}';
    }
}
