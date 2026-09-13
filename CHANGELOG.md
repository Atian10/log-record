# Changelog

本文件记录代码及发布准备的变更。设计文档中的 `v1.5` 是历史文档版号，不代表 Git 标签、Maven 版本或已经发布的制品。

## Unreleased

暂无新增条目。

## v2.0.0-rc.1 — 2026-09-13（预发布）

Git 标签指向 `1f0b7bd57c3be700556a37a37505e5f056f06e9f`。精确提交批次 `20260913-jitpack-1f0b7bd-release-r3` 与同提交标签批次 `20260913-jitpack-v2-0-0-rc1-release` 均通过 JitPack 远程发布及六个独立消费者验收；提供按模块选择的 JitPack 依赖坐标。对应 [GitHub Release](https://github.com/Atian10/log-record/releases/tag/v2.0.0-rc.1) 已发布并标记为 Pre-release。验证范围及历史失败见[公开发布说明](docs/公开发布说明.md)。

### POM 发布策略修复

- 三个库禁用 Gradle Module Metadata（`.module`），保留组件生成的完整 POM、Android Release 发布变体和 `withSourcesJar()`；POM 不得含 Metadata 重定向标记。固定 AGP 7.4.2、Gradle 7.5、Java 11，依赖版本不变。
- 模块坐标保持 `com.github.Atian10.log-record:<模块名>:<版本>`；源码通过 `sources` classifier 取得实际 `-sources.jar`，不再通过 `.module` 源码变体寻址。Android Kotlin BOM 1.8.0 以 POM 的 `type=pom、scope=import` 保留。
- 首次离线 POM 消费暴露标准库重复类；在 Android 发布方增加 `runtimeOnly` 的 `kotlin-stdlib-jdk7:1.8.0`、`kotlin-stdlib-jdk8:1.8.0`，补齐原定 1.8.0 对齐，不要求消费者手动添加 BOM 或强制版本规则。
- 本地入口增加离线限定验证；P5 使用默认 Maven／强制 POM 两种模式，共六个独立远端消费者。精确提交和标签各完成一组矩阵；这是 POM 策略验收，不代表旧 ModuleOnly 要求通过。
- 保留旧提交 `3febf8ceabb1f1701b91e2578fdaafaff30152d4` 的 P5 FAILED：三份远端 `.module` 坐标和源码变体地址错误，66 个 Java 源码条目与精确提交一致，六消费者未启动。首次离线 `20260913-pom-local` 的中间失败也保留；补齐运行时对齐后的 r2 和最终 `20260913-pom-local-final` 均通过四阶段与六消费者。修复提交先前远程运行分别因第三方依赖和 Wrapper 分发的 TLS 握手中断失败，原始记录与后续成功批次分别保留；未将多批局部结果拼成一次通过。

### 历史公开准备

- 保留原创代码的 MIT LICENSE，补充 Gradle Wrapper 的 Apache-2.0 正文、适用通知和运行依赖的第三方许可说明；SQLite JDBC 单列继承的 Zentus 许可。
- 为三个库的二进制及 sources 制品补充模块专属 MIT LICENSE，并为 POM 补充项目、SCM 和许可信息；生成结果由发布检查阶段核对。
- 纠正文档中的同步导出、清理策略刷新、已确认落盘数据的持久化边界，以及 AAR 文件依赖所需的 core、传递依赖和 Kotlin BOM 1.8.0。
- 增加 `PublicationArtifacts` 限定检查和三个独立消费者，保留原完整 `Artifacts` 验收要求。
- 增加 JitPack 验证的显式授权脚本及精确提交、另行授权标签的核验流程。原双元数据流程已执行一次并失败；当前改为 POM 发布后的复验要求，旧结果不覆盖。

### 基线已包含的修复

- flush 区分等待期间增量与目标累计结果，保留 FAILED、DROPPED 和 UNKNOWN，不把未确认结果报告成保存成功。
- 管理器关闭覆盖引擎、清理、活动操作与所属数据库；超时后继续收尾，完整终止后才允许重新初始化。
- 导出使用一致性快照和原子发布，保留首个错误并按归属清理临时文件，终态回调至多一次。
- 容量阶段校验 checkpoint、全局具体 ID 批次和物理回收结果；失败后的恢复轮次不继续容量删除。
- 修复 Gradle 7.5 Wrapper，统一 AGP 7.4.2 / JDK 11，保留按模块发布及 Android Kotlin BOM 对齐。

### 兼容性与验证范围

本版本要求 Java 11，保持 AGP 7.4.2、Gradle 7.5。清理失败、关闭、flush 结果和导出失败语义需要消费方明确处理。Git 标签版本与精确提交版本已分别验收，不能把一个版本的结果延伸到未经核验的后续标签。

2026-09-12 的 core 116 项、Desktop 33 项测试及构建结果属于历史证据，本版本没有重跑这些测试或全量 Lint。远程接入验收只覆盖元数据、源码、依赖解析、编译、APK/R8 等制品检查，未执行真机、模拟器、应用初始化、Room／业务数据库、真实业务数据或线上验收。
