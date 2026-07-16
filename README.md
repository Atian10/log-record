# log-record

跨平台日志记录库，支持 Android、Windows 桌面、Windows 服务器、Linux 服务器。

## 功能特性

- 多级别日志写入（DEBUG / INFO / WARN / ERROR / FATAL）
- 自定义日志类型（预设 + 无限扩展）
- 数据库持久化存储（Android Room / JDBC SQLite）
- 按条件查询、聚合统计、分页排序
- 日志导出（TXT / JSON / CSV，编码可选）
- 自动清理（按天数/容量/数量，可配置）
- 异步写入引擎（单线程 + 批处理）
- 运行时动态修改配置
- 异常独立存储与查询

## 模块说明

| 模块 | 说明 |
|------|------|
| `log-core` | 核心模块（纯 Java SE，零平台依赖） |
| `log-android` | Android 平台适配（Room + Logcat） |
| `log-desktop` | 桌面/服务器适配（JDBC SQLite + Console） |
| `log-sample` | 三平台使用示例 |

## 快速开始

### Android

```java
LogConfig config = new LogConfig.Builder()
    .addStorage(new RoomStorage(context))
    .versionTag("1.0.0")
    .build();
LogManager.init(context, config);

LogManager.get().i("MainActivity", "页面加载完成");
```

### 桌面/服务器

```java
LogConfig config = new LogConfig.Builder()
    .addStorage(new JdbcStorage("/var/log/logrecord.db"))
    .versionTag("1.0.0")
    .build();
LogManager.init(config);

LogManager.get().i("App", "服务启动");
```

## 文档

- [功能设计文档](docs/日志记录库-功能设计文档.md)
- [架构设计文档](docs/日志记录库-架构设计文档.md)

## 技术栈

- Java 1.8
- Gradle 7.6+
- Gson 2.10.1
- Room 2.5.2（Android）
- SQLite JDBC 3.42.0.0（桌面/服务器）

## License

MIT
