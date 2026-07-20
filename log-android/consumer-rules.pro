# log-android ProGuard/R8 keep 规则
#
# 说明：本文件通过 consumerProguardFiles 自动传递给消费方（Android 应用）。
# 来源：官方文档/经验推断
#   - Room：@Entity/@Dao/@Database 注解类需保留（来源：androidx.room ProGuard 官方）
#   - Gson：实体类字段反射访问（来源：google/gson ProGuard 官方文档）
#   - 本库 Storage 类：业务方通过 AndroidLogInit 装配，需保留公共 API

# ===== 基础保留属性（所有模块通用，建议保留） =====
-keepattributes Signature, *Annotation*, SourceFile, LineNumberTable, InnerClasses, EnclosingMethod
-keep public class * extends java.lang.Exception

# ===== Room Entity keep（Room 编译器生成的代码需要保留，来源：androidx.room 官方） =====
-keep class com.atian10.logrecord.android.room.** { *; }

# Room DAO 接口 keep（@Dao 注解类，Room 生成实现类）
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Database class * { *; }

# Room Database 实例 keep（反射创建数据库）
-keep class com.atian10.logrecord.android.room.LogDatabase { *; }
-keep class com.atian10.logrecord.android.room.LogDatabase$* { *; }

# ===== Storage 实现类 keep（业务方通过 AndroidLogInit 装配，需保留公共 API） =====
-keep class com.atian10.logrecord.android.RoomStorage { *; }
-keep class com.atian10.logrecord.android.RoomExceptionStorage { *; }
-keep class com.atian10.logrecord.android.LogcatStorage { *; }
-keep class com.atian10.logrecord.android.AndroidLogInit { *; }

# ===== Gson 模型 keep（JSON 序列化 user_fields，来源：google/gson ProGuard 官方文档） =====
-keepattributes Signature
-keepattributes *Annotation*

# Gson 处理泛型类型时需要保留 TypeToken 子类
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 枚举类 keep（switch/valueOf 需要）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
