# Changelog

本文件记录代码及发布准备的变更。设计文档中的 `v1.5` 是历史文档版号，不代表 Git 标签、Maven 版本或已经发布的制品。

## Unreleased

历史公开准备已收录于提交 `3febf8ceabb1f1701b91e2578fdaafaff30152d4`。该提交的 JitPack 构建成功，但 P5 发布元数据检查失败，尚未形成通过完整远程接入验收的版本。当前新增 POM 策略修复，结果与历史证据见[公开发布说明](docs/公开发布说明.md)。

### POM 发布策略修复

- 三个库禁用 Gradle Module Metadata（`.module`），保留组件生成的完整 POM、Android Release 发布变体和 `withSourcesJar()`；POM 不得含 Metadata 重定向标记。固定 AGP 7.4.2、Gradle 7.5、Java 11，依赖版本不变。
- 模块坐标保持 `com.github.Atian10.log-record:<模块名>:<版本>`；源码通过 `sources` classifier 取得实际 `-sources.jar`，不再通过 `.module` 源码变体寻址。Android Kotlin BOM 1.8.0 以 POM 的 `type=pom、scope=import` 保留。
- 首次离线 POM 消费暴露标准库重复类；在 Android 发布方增加 `runtimeOnly` 的 `kotlin-stdlib-jdk7:1.8.0`、`kotlin-stdlib-jdk8:1.8.0`，补齐原定 1.8.0 对齐，不要求消费者手动添加 BOM 或强制版本规则。
- 本地入口增加离线限定验证；更新产物检查及未来 P5 的默认 Maven／强制 POM 两种模式，共六个独立远端消费者。这是验收策略调整，不代表旧 ModuleOnly 要求通过。
- 保留旧 P5 FAILED：三份远端 `.module` 坐标和源码变体地址错误，66 个 Java 源码条目与精确提交一致，六消费者未启动。首次离线 `20260913-pom-local` 的中间失败也保留；补齐运行时对齐后的 `20260913-pom-local-r2` 四阶段通过，完善失败记录保护后的最终批次 `20260913-pom-local-final` 四阶段与六消费者再次通过。没有执行新远端验证或 Git／标签／Release 操作。

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

### 兼容性与版本规划

候选 `v2.0.0-rc.1` 仅为规划，尚未作为标签创建、推送或发布；旧精确提交在 JitPack 生成了制品，但未通过完整接入验收。考虑将候选置于 2.0 系列，是因为当前桌面制品要求 Java 11，且清理失败、关闭、flush 结果和导出失败语义需要消费方明确处理。未来先验证新的精确提交；若另行授权发布候选标签，还须以指向同一提交的标签重复验证，不能把提交验证冒充标签验证。

2026-09-12 的 core 116 项、Desktop 33 项测试及构建结果属于基线的历史证据，不表示本轮新增发布检查、远程消费或 Android 运行验收已经通过。
