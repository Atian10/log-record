# log-core ProGuard/R8 keep 规则
#
# 说明：log-core 是纯 Java SE 模块（java-library 插件），不自动传递 pro 规则。
# 本文件供消费方（Android/桌面应用）在自身的 proguard-rules.pro 中参考引用。
# 推荐方式：在消费方 build.gradle 中配置
#   android.buildTypes.release.consumerProguardFiles '../log-core/proguard-rules.pro'
# 或直接将以下规则复制到消费方的 proguard-rules.pro。
#
# 来源：官方文档/经验推断
#   - Gson：实体类字段反射访问（来源：google/gson ProGuard 官方文档）
#   - 本库核心模型：LogRecord/ExceptionRecord 通过 Gson 序列化 user_fields，字段名必须保留
#   - 本库接口：消费方实现 IStorage/IFormatter/ILogFilter 等需保留
#   - 枚举：LogLevel/OrderBy/ExportFormat 等用于 switch 和反射，需保留

# ===== 基础保留属性（所有模块通用，建议保留） =====
-keepattributes Signature, *Annotation*, SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception

# ===== 核心数据模型 keep（Gson 反射序列化 user_fields，字段名必须保留） =====
-keep class com.atian10.logrecord.core.model.** { *; }

# ===== 枚举类 keep（switch/反射用，valueOf/values 必须保留） =====
-keep class com.atian10.logrecord.core.model.LogLevel { *; }
-keep class com.atian10.logrecord.core.model.StandardLogType { *; }
-keep class com.atian10.logrecord.core.query.OrderBy { *; }
-keep class com.atian10.logrecord.core.export.ExportFormat { *; }
-keep class com.atian10.logrecord.core.export.ExportEncoding { *; }
-keep class com.atian10.logrecord.core.engine.QueueFullPolicy { *; }
-keep class com.atian10.logrecord.core.engine.LogStatus$State { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===== 核心接口 keep（消费方实现需保留） =====
-keep class com.atian10.logrecord.core.ILogger { *; }
-keep class com.atian10.logrecord.core.ILoggerEngine { *; }
-keep class com.atian10.logrecord.core.IStorage { *; }
-keep class com.atian10.logrecord.core.IExceptionStorage { *; }
-keep class com.atian10.logrecord.core.IFormatter { *; }
-keep class com.atian10.logrecord.core.ILogFilter { *; }

# ===== 回调接口 keep（业务方实现需保留） =====
-keep class com.atian10.logrecord.core.export.ExportCallback { *; }
-keep class com.atian10.logrecord.core.clean.CleanCallback { *; }

# ===== 配置类 keep（Builder 模式反射、字段反射需要） =====
-keep class com.atian10.logrecord.core.config.** { *; }
-keepclassmembers class com.atian10.logrecord.core.config.**$Builder { *; }

# ===== 查询类 keep =====
-keep class com.atian10.logrecord.core.query.** { *; }

# ===== 引擎类 keep（含 LogStatus/LogEntry 等公开类型） =====
-keep class com.atian10.logrecord.core.engine.** { *; }

# ===== LogManager 门面 keep（业务方通过 LogManager.get() 调用所有公共 API） =====
-keep class com.atian10.logrecord.core.LogManager { *; }
-keepclassmembers class com.atian10.logrecord.core.LogManager {
    public *;
}

# ===== Gson 序列化保留泛型签名（来源：google/gson ProGuard 官方文档） =====
-keepattributes Signature
-keepattributes *Annotation*

# Gson 处理泛型类型时需要保留 TypeToken 子类
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
