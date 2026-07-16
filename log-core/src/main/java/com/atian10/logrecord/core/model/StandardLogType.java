package com.atian10.logrecord.core.model;

/**
 * 预设日志类型枚举
 * <p>
 * 内置 5 种预设类型，业务方可通过 {@link #code()} 获取字符串值。
 * 业务方也可直接传字符串自定义类型，无需注册。
 * </p>
 */
public enum StandardLogType {

    /** 网络日志 */
    NETWORK("NETWORK"),

    /** 界面日志 */
    UI("UI"),

    /** 数据库日志 */
    DATABASE("DB"),

    /** 业务日志 */
    BUSINESS("BUSINESS"),

    /** 系统日志 */
    SYSTEM("SYSTEM");

    private final String code;

    StandardLogType(String code) {
        this.code = code;
    }

    /**
     * 获取类型编码
     * @return 类型字符串
     */
    public String code() {
        return code;
    }
}
