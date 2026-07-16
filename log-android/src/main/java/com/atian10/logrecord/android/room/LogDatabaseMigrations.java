package com.atian10.logrecord.android.room;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * 数据库迁移集合
 * <p>
 * 当前版本 v1，无迁移。后续版本新增字段/表时在此添加 {@link Migration} 实例。
 * {@code AndroidLogInit} 会将这些迁移注册到 Room.databaseBuilder。
 * </p>
 * <p>
 * 版本规划：
 * <ul>
 *   <li>v1：初始版本（log_record + exception_table）</li>
 *   <li>v2+：后续新增字段/表时在此添加 Migration_1_2、Migration_2_3 等</li>
 * </ul>
 * </p>
 */
public final class LogDatabaseMigrations {

    private LogDatabaseMigrations() {
        // 工具类禁止实例化
    }

    /**
     * 获取所有迁移
     * @return 迁移数组（当前为空）
     */
    public static Migration[] getAll() {
        return new Migration[0];
    }

    // 示例：后续版本新增迁移时按以下格式添加
    // public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
    //     @Override
    //     public void migrate(SupportSQLiteDatabase database) {
    //         // ALTER TABLE log_record ADD COLUMN new_field TEXT;
    //     }
    // };
}
