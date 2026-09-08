package com.atian10.logrecord.desktop.jdbc;

import java.sql.SQLException;

/**
 * 批量事务已确认回滚的 SQLException
 * <p>
 * {@link JdbcHelper#executeBatch(String, Object[][])} 在批量失败且回滚成功后抛出，
 * 表示事务内的修改确定未提交，调用方可以安全地重放同一批数据。
 * </p>
 * <p>
 * 与之相对，普通 {@link SQLException} 表示提交结果不确定或回滚失败，
 * 调用方禁止自动重放，避免重复写入。
 * </p>
 */
public class BatchRolledBackException extends SQLException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造已确认回滚异常
     *
     * @param message 描述信息
     * @param cause 批量执行的原始失败原因
     */
    public BatchRolledBackException(String message, Throwable cause) {
        super(message, cause);
    }
}
