package com.atian10.logrecord.android.room;

import com.atian10.logrecord.core.DatabaseOperationGuard;

import androidx.room.Database;
import androidx.room.RoomDatabase;

/**
 * 日志 Room 数据库
 * <p>
 * 包含日志表和异常表，version=1。开启 WAL 模式以支持并发读写。
 * 由 {@code AndroidLogInit} 通过 {@code Room.databaseBuilder} 构建实例，
 * 并调用 {@code setWriteAheadLoggingEnabled(true)} 开启 WAL。
 * </p>
 */
@Database(
        entities = {LogEntity.class, ExceptionEntity.class},
        version = 1,
        exportSchema = true
)
public abstract class LogDatabase extends RoomDatabase {
    /** Room 实例的唯一操作保护，所有表和管理器必须共享。 */
    private final DatabaseOperationGuard operationGuard = new DatabaseOperationGuard();
    /** 提供平台接线使用的保护实例。 */
    public DatabaseOperationGuard getOperationGuard() { return operationGuard; }
    /** 所有托管操作退出后才关闭 Room，不持 Room 内部锁等待许可。 */
    @Override public void close() {
        operationGuard.beginResourceClose();
        if (!operationGuard.awaitIdle(Long.MAX_VALUE)) throw new IllegalStateException("database still active");
        super.close();
    }

    /**
     * 日志 DAO
     */
    public abstract LogDao logDao();

    /**
     * 异常 DAO
     */
    public abstract ExceptionDao exceptionDao();
}
