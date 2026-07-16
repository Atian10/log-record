package com.atian10.logrecord.android.room;

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

    /**
     * 日志 DAO
     */
    public abstract LogDao logDao();

    /**
     * 异常 DAO
     */
    public abstract ExceptionDao exceptionDao();
}
