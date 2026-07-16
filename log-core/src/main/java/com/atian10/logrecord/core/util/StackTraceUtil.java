package com.atian10.logrecord.core.util;

/**
 * 堆栈工具类
 * <p>
 * 提供调用者方法名/行号捕获，以及 Throwable 到字符串的转换。
 * 用于 LogRecord 中 methodName/lineNumber 捕获，以及 ExceptionRecord 中 stackTrace 字符串化。
 * </p>
 */
public final class StackTraceUtil {

    private StackTraceUtil() {
        // 工具类禁止实例化
    }

    /**
     * 调用者信息
     */
    public static final class Caller {

        /** 方法名；无法捕获时为 null */
        public final String methodName;
        /** 行号；无法捕获时为 0 */
        public final int lineNumber;

        public Caller(String methodName, int lineNumber) {
            this.methodName = methodName;
            this.lineNumber = lineNumber;
        }
    }

    /**
     * 捕获调用者方法名与行号
     * <p>
     * 跳过指定层数栈帧后取当前调用者信息。
     * 典型调用链：业务代码 → ILogger.log → LogManager.log → 捕获方法。
     * 需根据实际调用层数传入合适的 skipFrames。
     * </p>
     * @param skipFrames 需要跳过的栈帧数（不包含本方法本身）
     * @return 调用者信息；无法获取时返回 Caller(null, 0)
     */
    public static Caller captureCaller(int skipFrames) {
        // +1 跳过本方法自身栈帧
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int index = skipFrames + 1;
        if (stack == null || index < 0 || index >= stack.length) {
            return new Caller(null, 0);
        }
        StackTraceElement element = stack[index];
        return new Caller(element.getMethodName(), element.getLineNumber());
    }

    /**
     * 将 Throwable 的堆栈转为字符串
     * @param throwable 异常
     * @return 堆栈字符串；throwable 为 null 时返回空串
     */
    public static String stackTraceToString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * 获取异常类名
     * @param throwable 异常
     * @return 类名；throwable 为 null 时返回 null
     */
    public static String getClassName(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        return throwable.getClass().getName();
    }

    /**
     * 获取异常消息
     * @param throwable 异常
     * @return 消息字符串；throwable 为 null 或无消息时返回 null
     */
    public static String getMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        return throwable.getMessage();
    }
}
