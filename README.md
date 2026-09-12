# log-record

跨平台日志记录库，支持 Android、Windows 桌面、Windows 服务器、Linux 服务器。

## 功能特性

- 多级别日志写入（DEBUG / INFO / WARN / ERROR / FATAL）
- 自定义日志类型（预设 + 无限扩展）
- 数据库持久化存储（Android Room / JDBC SQLite）
- 按条件查询、聚合统计、分页排序
- 日志导出（TXT / JSON / CSV，编码可选；一致性快照边界 + 统一失败契约 + 原子发布）
- 自动清理（表级按天数/数量 + 全库磁盘容量预算 `databaseMaxSizeMB`，旧容量参数自动迁移）
- 异步写入引擎（单线程 + 批处理；flush/shutdown 带超时结果 `FlushResult`/`ShutdownResult`）
- 运行时动态修改配置（含 formatter，导出按次生效）
- 异常独立存储与查询

## 模块说明

| 模块 | 说明 |
|------|------|
| `log-core` | 核心模块（纯 Java SE，零平台依赖） |
| `log-android` | Android 平台适配（Room + Logcat） |
| `log-desktop` | 桌面/服务器适配（JDBC SQLite + Console） |
| `log-sample` | 桌面/服务器使用示例 |
| `log-sample-android` | Android 使用示例 |

## 接入方式

### Android

**方式一：JitPack 远程依赖（推荐）**

第 1 步：在**项目根目录** `build.gradle` 添加 JitPack 仓库：

```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

> 如果使用 `settings.gradle` 的 `dependencyResolutionManagement`，请在 `repositories` 中添加 `maven { url 'https://jitpack.io' }`。

第 2 步：在 **app 模块** `build.gradle` 添加依赖：

```gradle
dependencies {
    implementation 'com.github.Atian10:log-record:v1.5'
}
```

> 访问 [JitPack](https://jitpack.io/com/github/Atian10/log-record) 查看可用版本。首次拉取会触发构建，约 2-3 分钟。

**方式二：本地 Module 依赖**

将 `log-android` 和 `log-core` 模块引入你的项目：

```gradle
dependencies {
    implementation project(':log-android')
}
```

**方式三：AAR 文件依赖**

```bash
# 编译产出 AAR
gradle :log-android:assembleRelease
# 产出路径：log-android/build/outputs/aar/log-android-release.aar
```

将 AAR 放入 app 模块 `libs/` 目录：

```gradle
dependencies {
    implementation files('libs/log-android-release.aar')
}
```

### 桌面/服务器

**JAR 依赖：**

```bash
# 编译产出 JAR
gradle :log-core:jar :log-desktop:jar
# 产出路径：log-core/build/libs/log-core.jar, log-desktop/build/libs/log-desktop.jar
```

```gradle
dependencies {
    implementation files('libs/log-core.jar', 'libs/log-desktop.jar')
}
```

## 当前工作区修复说明

本轮修改包含 flush 目标累计结果及 UNKNOWN、活动操作关闭保护、原子导出发布和容量失败后的仅恢复状态。当前仅完成源码与文档的静态复核，未执行本轮编译、测试、打包或设备验证；发布版本和已有制品是否包含这些修复，需按其对应源码提交另行核对。

- `FlushResult.getSaved()/getFailed()/getDropped()/getUnknown()` 是等待期间增量；`getTargetSaved()/getTargetFailed()/getTargetDropped()/getTargetUnknown()` 包含调用前的历史结果。`isAllPersisted()` 才判断整个目标是否保存成功。
- `shutdown(0)` 只发起关闭并立即查询状态；管理器等待写入、清理和活动快照结束后关闭其平台数据库。超时结果可由后续调用更新。
- 容量结果由 `LogManager.get().getLastCapacityResult()` 获取；仅 MET 表示达标，其他结局走清理失败回调。详细边界见[使用文档](docs/使用文档.md)。

## 快速开始

### Android

```java
// 1. 初始化（Application.onCreate）
AndroidLogInit.init(this, new LogConfig.Builder()
    .versionTag("1.0.0")
    .consoleEnabled(true)        // 输出到 Logcat
    .captureMethodLine(true)     // 捕获方法名/行号
    .cleanPolicy(CleanPolicy.builder()
        .enable(true)
        .keepDays(7)
        .maxDbSizeMB(50)
        .build())
    .build());

// 2. 写日志
LogManager.get().i("MainActivity", "页面加载完成");
LogManager.get().e("NetworkService", "请求失败", throwable);

// 3. 查日志（必须在子线程！）
new Thread(() -> {
    List<LogRecord> records = LogManager.get().queryLogs(
        LogQuery.builder()
            .level(LogLevel.ERROR)
            .limit(50)
            .build());
}).start();

// 4. 导出日志（含数据库与文件 IO，必须在子线程！）
//    cleanNow / flush(long) / shutdown(long) 同样为阻塞或数据库操作，勿在主线程调用
backgroundExecutor.execute(() ->
    LogManager.get().exportLogs(
        LogQuery.builder().build(),
        ExportFormat.JSON,
        outputPath,
        callback));
```

### 桌面/服务器

```java
// 1. 初始化
DesktopLogInit.init("/var/log/myapp/logs.db", new LogConfig.Builder()
    .versionTag("1.0.0")
    .cleanPolicy(CleanPolicy.builder()
        .enable(true)
        .keepDays(30)
        .build())
    .build());

// 2. 写日志
LogManager.get().i("App", "服务启动");

// 3. 优雅关闭
DesktopLogInit.shutdown();
```

## ProGuard / R8 混淆

Android 接入方无需额外配置，`log-android` 的 `consumer-rules.pro` 会自动传递混淆规则。

如使用本地 AAR 接入，确保 AAR 包含 `proguard.txt`（release 构建会自动生成）。

## 文档

- [使用文档](docs/使用文档.md) - 完整接入指南与 API 手册
- [功能设计文档](docs/日志记录库-功能设计文档.md) - 96 项功能详细说明
- [架构设计文档](docs/日志记录库-架构设计文档.md) - 分层架构与设计决策

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| Java | Java SE | 1.8 |
| Gradle | Gradle | 7.6+ |
| JSON | Gson | 2.10.1 |
| Android DB | Room | 2.5.2 |
| 桌面 DB | SQLite JDBC | 3.42.0.0 |
| Android compileSdk | - | 33 |
| Android minSdk | - | 21（Android 5.0） |

## 系统要求

- Android 5.0+（API 21+）
- Java 8+（桌面/服务器）
- Gradle 7.6+

## License

[MIT](LICENSE)
