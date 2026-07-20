# log-desktop ProGuard/R8 keep 规则
#
# 说明：log-desktop 是纯 Java SE 模块（java-library 插件），不自动传递 pro 规则。
# 本文件供消费方（桌面/服务器应用）在自身的 proguard-rules.pro 中参考引用。
# 推荐方式：直接将以下规则复制到消费方的 proguard-rules.pro。
#
# 来源：官方文档/经验推断
#   - sqlite-jdbc：驱动类通过 Class.forName 反射加载（来源：xerial/sqlite-jdbc README）
#   - JDBC 接口：java.sql.* 为 JDK 内置，无需 keep
#   - 本库 Storage 类：业务方通过 AndroidLogInit/DesktopLogInit 反射或直接调用

# ===== 基础保留属性（所有模块通用，建议保留） =====
-keepattributes Signature, *Annotation*, SourceFile, LineNumberTable, InnerClasses, EnclosingMethod
-keep public class * extends java.lang.Exception

# ===== SQLite JDBC 驱动 keep（反射加载驱动类，来源：xerial/sqlite-jdbc 官方） =====
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**
-keep class org.sqlite.JDBC { <init>(...); }

# 防止 JDBC 驱动服务加载被裁剪
-keep class java.sql.DriverManager { *; }

# ===== log-desktop 实现类 keep =====

# JDBC 工具类（含 Connection 管理、WAL 模式、回调 ResultSetHandler）
-keep class com.atian10.logrecord.desktop.jdbc.** { *; }

# Storage 实现类（业务方通过 DesktopLogInit 装配，需保留公共 API）
-keep class com.atian10.logrecord.desktop.JdbcStorage { *; }
-keep class com.atian10.logrecord.desktop.JdbcExceptionStorage { *; }
-keep class com.atian10.logrecord.desktop.ConsoleStorage { *; }
-keep class com.atian10.logrecord.desktop.DesktopLogInit { *; }
