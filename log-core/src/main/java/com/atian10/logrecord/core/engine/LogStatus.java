package com.atian10.logrecord.core.engine;

/**
 * 库状态类
 * <p>
 * 描述日志库当前运行状态。不可变对象。
 * 状态分为三档：NORMAL（正常）、DEGRADED（降级）、ERROR（异常）。
 * </p>
 */
public final class LogStatus {

    /**
     * 状态枚举
     */
    public enum State {
        /** 正常运行 */
        NORMAL,
        /** 降级运行（如未初始化、数据库初始化失败，降级输出到 System.out） */
        DEGRADED,
        /** 异常状态（如引擎已关闭） */
        ERROR
    }

    private final State state;
    private final String message;
    private final long warningCount;

    /**
     * 构造库状态
     * @param state 状态
     * @param message 状态描述
     * @param warningCount 内部告警计数
     */
    public LogStatus(State state, String message, long warningCount) {
        this.state = state;
        this.message = message;
        this.warningCount = warningCount;
    }

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public long getWarningCount() {
        return warningCount;
    }

    /**
     * 是否正常状态
     */
    public boolean isNormal() {
        return state == State.NORMAL;
    }

    /**
     * 是否降级状态
     */
    public boolean isDegraded() {
        return state == State.DEGRADED;
    }

    /**
     * 是否异常状态
     */
    public boolean isError() {
        return state == State.ERROR;
    }

    @Override
    public String toString() {
        return "LogStatus{"
                + "state=" + state
                + ", message='" + message + '\''
                + ", warningCount=" + warningCount
                + '}';
    }
}
