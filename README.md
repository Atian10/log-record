# log-record

跨平台日志记录库，支持 Android、Windows 桌面、Windows 服务器、Linux 服务器。

已发布候选版本：[v2.0.0-rc.1](https://github.com/Atian10/log-record/releases/tag/v2.0.0-rc.1)（Pre-release），Git 标签指向提交 `1f0b7bd57c3be700556a37a37505e5f056f06e9f`。精确提交和标签版本均已通过 JitPack 远程发布及独立消费者的编译、打包和依赖检查，可按下方模块坐标接入。验证不包括真机、模拟器、数据库运行或线上业务验收；完整范围与历史失败见[公开发布说明](docs/公开发布说明.md)。设计文档中的 `v1.5` 是历史文档版号。

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

**方式一：按模块使用 Maven 依赖**

第 1 步：添加 JitPack 仓库。下例用于在**项目根目录** `build.gradle` 管理仓库的项目；如果项目使用集中仓库管理，请将同样的仓库条目放入 `settings.gradle` 的 `dependencyResolutionManagement.repositories`：

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
    implementation 'com.github.Atian10.log-record:log-android:v2.0.0-rc.1'
}
```

> 上述版本已完成精确提交和同提交标签两轮远程验收。Android 接入只声明 `log-android`，由 POM 传递 core 等依赖；不要使用聚合坐标同时引入 Android 与 Desktop。更换版本后需重新核对该版本的验证状态。

三个库通过 Maven POM 描述模块坐标和传递依赖，并以 `sources` classifier 提供源码，例如 `log-core-v2.0.0-rc.1-sources.jar`。本版本不发布 Gradle Module Metadata（`.module`），POM 不含其重定向标记；Java 11 要求通过字节码和消费者构建检查。P5 已分别验证默认 Maven 解析和强制 POM 解析；这是 POM 发布策略验收，不代表旧 ModuleOnly 验收通过。

Android 库保留 Kotlin BOM 1.8.0，并显式发布 `kotlin-stdlib-jdk7:1.8.0` 和 `kotlin-stdlib-jdk8:1.8.0` 两项运行时依赖，补齐 POM 消费时的标准库对齐，不要求 Maven 接入方另加 BOM 或强制版本规则。首次离线验证发现仅保留 BOM 时仍混入旧 jdk7/jdk8 1.7.20，后续复验结果见公开发布说明；目标版本仍为既定 1.8.0。

**方式二：本地 Module 依赖**

将 `log-android` 和 `log-core` 模块引入你的项目：

```gradle
dependencies {
    implementation project(':log-android')
}
```

**方式三：AAR 文件依赖（v2.0.0-rc.1）**

下载本次已发布的两个文件，保留原文件名，放入 app 模块 `libs/` 目录：

- [log-android-v2.0.0-rc.1.aar](https://jitpack.io/com/github/Atian10/log-record/log-android/v2.0.0-rc.1/log-android-v2.0.0-rc.1.aar)
- [log-core-v2.0.0-rc.1.jar](https://jitpack.io/com/github/Atian10/log-record/log-core/v2.0.0-rc.1/log-core-v2.0.0-rc.1.jar)

```gradle
dependencies {
    implementation files('libs/log-android-v2.0.0-rc.1.aar')
    implementation files('libs/log-core-v2.0.0-rc.1.jar')
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation 'androidx.room:room-runtime:2.5.2'
    implementation 'androidx.annotation:annotation:1.6.0'
    implementation platform('org.jetbrains.kotlin:kotlin-bom:1.8.0')
    runtimeOnly 'org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0'
    runtimeOnly 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0'
}
```

单个 AAR 不包含 `log-core`，文件依赖也不会读取发布 POM 中的依赖及版本约束。上例两个库文件均使用 `v2.0.0-rc.1`，其余声明对应已发布 POM 的依赖；Kotlin BOM 和 jdk7/jdk8 的 1.8.0 对齐声明须一并保留。Room 等 Maven 依赖继续解析其传递依赖，因此项目仍需配置 `google()` 和 `mavenCentral()`。全离线文件接入还需按实际解析图备齐传递制品及许可。已完成的独立消费编译采用 Maven 方式，手动文件接入未另行编译验证；优先使用上面的 Maven 依赖。

自行从源码构建可运行 `./gradlew :log-android:assembleRelease :log-core:jar`。未显式指定构建版本时，输出为 `log-android/build/outputs/aar/log-android-release.aar` 和 `log-core/build/libs/log-core-0.0.0-local-validation.jar`，引用时按实际文件名调整；这些本地输出与上面的远端发行文件分别命名。

### 桌面/服务器

**Maven 依赖：**

```gradle
dependencies {
    implementation 'com.github.Atian10.log-record:log-desktop:v2.0.0-rc.1'
}
```

此依赖传递引入 `log-core`、Gson 和 SQLite JDBC，不引入 Android 模块。上面的标签版本已通过默认 Maven／强制 POM 两种模式的独立 Java 消费者编译与依赖检查。

**JAR 文件依赖（v2.0.0-rc.1）：**

下载以下两个文件并保留原文件名，放入消费模块的 `libs/` 目录：

- [log-core-v2.0.0-rc.1.jar](https://jitpack.io/com/github/Atian10/log-record/log-core/v2.0.0-rc.1/log-core-v2.0.0-rc.1.jar)
- [log-desktop-v2.0.0-rc.1.jar](https://jitpack.io/com/github/Atian10/log-record/log-desktop/v2.0.0-rc.1/log-desktop-v2.0.0-rc.1.jar)

```gradle
dependencies {
    implementation files('libs/log-core-v2.0.0-rc.1.jar', 'libs/log-desktop-v2.0.0-rc.1.jar')
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation 'org.xerial:sqlite-jdbc:3.42.0.0'
}
```

Gson 和 SQLite JDBC 仍通过 `mavenCentral()` 解析；手动文件接入未另行编译验证。自行从源码构建可运行 `./gradlew :log-core:jar :log-desktop:jar`，默认产物为各模块 `build/libs/` 下的 `log-core-0.0.0-local-validation.jar`、`log-desktop-0.0.0-local-validation.jar`；本地构建版本与本次已发布版本分别使用。

## 修复、验证与版本状态

当前源码包含 flush 目标累计结果及 UNKNOWN、活动操作关闭保护、原子导出发布和容量失败后的仅恢复状态。2026-09-12 的历史验证对应提交 `76497adcfd40aa2c0720464197b64b833f55a259` 所收录的构建输入：固定 AGP 7.4.2、Gradle 7.5、JDK 11；core 116 项、Desktop 33 项测试通过且无跳过；Android Debug/Release 与 R8 构建、四个 Lint 变体、三个库的项目内 Maven 发布、两个独立消费工程编译通过。Lint 为 0 错误，保留 4 类现有警告（两个变体合计 8 条）。

该历史批次修复 Wrapper 启动入口、Java 11 编译/发布配置及 Android 消费方 Kotlin 标准库版本冲突，Room schema 保持一致。本机证据位于 `build/verification/20260912-java11-aligned`，不纳入 Git。后续公开准备检查和 POM 修复的离线六消费者检查分别通过；旧提交 P5 FAILED、首次离线失败、远程 TLS 中断与后续成功批次均保留，详见[公开发布说明](docs/公开发布说明.md)。本版本精确提交的通过批次为 `20260913-jitpack-1f0b7bd-release-r3`，标签版本的通过批次为 `20260913-jitpack-v2-0-0-rc1-release`，不使用历史测试代替当前远程证据。

- `FlushResult.getSaved()/getFailed()/getDropped()/getUnknown()` 是等待期间增量；`getTargetSaved()/getTargetFailed()/getTargetDropped()/getTargetUnknown()` 包含调用前的历史结果。`isAllPersisted()` 才判断整个目标是否保存成功。
- `shutdown(0)` 只发起关闭并立即查询状态；管理器等待写入、清理和活动快照结束后关闭其平台数据库。超时结果可由后续调用更新。
- 容量结果由 `LogManager.get().getLastCapacityResult()` 获取；仅 MET 表示达标，其他结局走清理失败回调。详细边界见[使用文档](docs/使用文档.md)。
- `exportLogs`/`exportExceptions` 同步执行，Android 业务方须调度到后台线程；更新清理策略后调用同一管理器的 `refreshCleanPolicies()`，使 CleanTask 采用新策略。

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
        .build()));

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
        .build()));

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
- [公开发布说明](docs/公开发布说明.md) - 许可范围、版本策略、发布检查与证据边界
- [变更记录](CHANGELOG.md) - 版本变更与兼容性说明

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| Java | 构建 JDK / 源码与目标字节码 | 11 |
| Gradle | Wrapper | 固定 7.5 |
| Android Gradle Plugin | AGP | 固定 7.4.2 |
| JSON | Gson | 2.10.1 |
| Android DB | Room | 2.5.2 |
| 桌面 DB | SQLite JDBC | 3.42.0.0 |
| Android compileSdk | - | 33 |
| Android minSdk | - | 21（Android 5.0） |

## 系统要求

- Android 5.0+（API 21+）
- 构建与本轮验证固定使用 JDK 11，桌面制品最低要求 Java 11；本轮不验证其他 JVM 版本。
- 使用仓库 Wrapper：Gradle 7.5，AGP 7.4.2。Windows 命令可用 `./gradlew.bat`。
- Android Studio 的 Gradle JDK 选择本机已有的 JDK 11；不提交本机绝对 JDK/SDK 路径。

## 非真机验证入口

在项目根目录使用 PowerShell 7，传入本机已经安装的工具目录：

```powershell
./scripts/verify-nondevice.ps1 -JavaHome 'E:\Java\temurin-11' -SdkDirectory 'E:\AndroidDev\Sdk'
```

完整入口执行 JVM 合成测试、Android Debug/Release 构建和 Lint、临时 Maven 发布与六个独立消费工程编译，并核对制品和 Room schema。报告、数据库、临时签名、消费者独立可写缓存与制品写入 `build/verification/<RunId>`；已有仓库缓存位于 `.gradle/verification-home`。脚本不安装 SDK/JDK；本版本发布准备未重跑完整入口，执行范围是下述限定阶段和独立 P5。

POM 修复的离线限定本地检查使用 `-Offline -Stages Environment,Publish,Consumers,PublicationArtifacts`，只执行对应阶段。必须已有固定 Wrapper 分发和所需依赖，缺失即停止，不自动下载；不以旧测试结果充当新输入的结果。本地按默认 Maven／强制 POM 各验证三个消费者，使用独立可写缓存及已有第三方依赖的只读种子缓存。远端 P5 另用每批六组独立空缓存验证精确提交和标签版本；两类证据分别保存。`PublicationArtifacts` 与完整验收的 `Artifacts` 分开，详细入口和证据要求见[公开发布说明](docs/公开发布说明.md)。

本版本验收排除真机、模拟器、ADB、应用安装与运行、初始化、Room／业务数据库执行、真实业务数据和正式签名；未完成线上业务验收。Git 标签和 JitPack 接入验证不代替这些层次，实际通过范围以对应报告为准。

## License

本项目原创代码使用 [MIT](LICENSE)。随源码分发的 Gradle Wrapper 使用 Apache-2.0；运行依赖保留各自许可。详见 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)、[Apache-2.0 正文](licenses/Apache-2.0.txt)和 [Wrapper 通知](licenses/Gradle-Wrapper-NOTICE.txt)。许可适用范围与版本验收范围分别说明。
