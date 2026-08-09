# ShopMall 管理端日志系统设计

## 1. 目标与结论

本方案在现有 Kotlin/Spring Boot 后端和 `AdminPanelUI` 上增加一套应用日志管理能力，覆盖以下需求：

| 需求 | 设计结论 |
| --- | --- |
| 在线调整日志等级 | 通过 Logback `LoggerContext` 即时修改根日志等级和指定 logger 的覆盖等级，不重启应用。 |
| 实时查看日志 | 后端维护有界内存环形缓冲区，管理端使用带游标的长轮询获取新日志。 |
| 查看历史日志 | 后端只读扫描受控日志根目录，按日期和文件分页读取 UTF-8 日志。 |
| 自定义输出格式 | 管理员编辑受限的输出模板；模板经服务端编译校验后同时用于文件和实时日志。 |
| 指定存储路径 | 由部署配置 `LOG_STORAGE_PATH` 指定，运行时不能通过管理端修改。 |
| 年/月/日目录 | 文件写入 `${LOG_STORAGE_PATH}/yyyy/MM/dd/`。跨自然日时主动切换到新目录。 |
| ISO 时间文件名 | 使用跨平台安全的 ISO-8601 基本格式 `yyyyMMdd'T'HHmmss.SSS-x.log`。 |
| 每次启动新文件 | 每个应用进程启动时生成新的文件组时间，首个文件固定为 `-0.log`，绝不续写上次启动的文件。 |
| 单文件 10 MB 轮转 | 写入下一条日志前检查编码后字节数；预计超过 `10 MiB` 时先关闭当前文件并打开 `x + 1`。 |

运行时日志设置保存在现有 Redis 中，并沿用项目已经使用的版本号和乐观锁模式。日志正文只写文件和本机内存缓冲区，不写 PostgreSQL 或 Redis，不新增数据库实体及迁移脚本。

## 2. 与现有项目的衔接

| 现有能力 | 本方案的用法 |
| --- | --- |
| Spring Boot 4.1、SLF4J 和默认 Logback | 保留业务代码中的 `LoggerFactory` 用法，新增 Logback 配置与项目专用 appender。 |
| `StringRedisTemplate` | 保存日志等级、logger 覆盖项、输出模板、版本和修改审计信息。 |
| `/admin/api/**` 的 `ROLE_ADMIN` 保护 | 所有日志管理接口自动进入现有 Spring Security 管理端边界。 |
| `AdminAccessService` | 服务层再次执行管理员校验，保持项目现有的纵深授权方式。 |
| `ResponseBuilder` 和统一 `shared.Response` | 设置、实时长轮询和历史查询都返回现有统一响应信封。 |
| 管理端 Nuxt、Naive UI、Lucide 和 `useHttp` | 新增“日志中心”页面、类型和 composable，不引入第二套 HTTP 客户端。 |
| 限速设置的 Redis 版本控制模式 | 日志设置复用“部署默认值 + Redis 运行时值 + `expected_version` 冲突检测”的交互语义。 |

当前仓库没有 `logback-spring.xml`，因此仍使用 Spring Boot 默认控制台日志。新增文件日志后继续保留控制台作为故障兜底，但管理端自定义模板只影响项目文件日志和管理端实时展示；控制台格式保持部署期静态配置，避免运行时修改破坏容器日志采集规则。

## 3. 范围与边界

### 3.1 本期范围

- 调整根 logger 的 `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`OFF` 等级；
- 为具体包名或类名增加、更新和删除等级覆盖；
- 保存并热更新一个全局输出模板；
- 按日志事件实际生效后的等级捕获实时日志；
- 按日期列出文件、查看活动文件或已关闭文件的历史内容；
- 在重启、跨日、文件达到大小上限和格式变更时正确轮转；
- 记录日志设置修改审计和运行指标。

### 3.2 本期不做

- 不允许从管理端修改日志根目录、时区、单文件大小和保留天数；这些都是部署级设置；
- 不提供管理端删除、重命名或覆盖日志文件的接口；
- 不把日志正文保存到数据库或 Redis；
- 不实现 Elasticsearch、Loki、OpenSearch 等集中检索；
- 不承诺在多后端节点之间集中展示实时日志或历史文件。运行时设置是集群一致的，日志正文仍是节点本地的；
- 不提供任意正则表达式历史搜索，避免 ReDoS 和不可控磁盘扫描。

## 4. 总体架构

```text
业务代码 LoggerFactory / SLF4J
            |
            v
      Logback LoggerContext
        |              |
        |              +--> 固定 ConsoleAppender（部署故障兜底）
        |
        +--> ShopMallLogAppender
               |-- 有界输入队列（事件数 + 估算字节数）
               |       |
               |       `-- 格式化/实时发布后台 worker
               |              |-- LiveLogBuffer（有界内存环形缓冲区）
               |              `-- 有界文件队列（事件数 + 估算字节数）
               |                     |
               |                     `-- 文件后台 worker
               |                            `-- RotatingLogFileWriter
               |                                   `-- UTF-8 日志文件
               |
               `-- AdminLogController 长轮询（只读 LiveLogBuffer）

AdminPanelUI
  |-- 日志设置 --PUT--> LoggingSettingsService --CAS--> Redis Hash
  |                                      |                 |
  |                                      v                 `-- Pub/Sub 通知其他节点
  |                              RuntimeLoggingManager
  |                                      |
  |                                      `-- LoggerContext / ShopMallLogAppender
  |-- 实时日志 --GET long poll--> LiveLogBuffer
  `-- 历史日志 --GET-----------> LogHistoryService --> 日志根目录
```

建议的后端职责如下：

| 组件 | 职责 |
| --- | --- |
| `LoggingProperties` | 绑定和校验部署级默认值、路径、时区、缓冲区与保留策略。 |
| `ShopMallLogAppender` | 业务线程有界捕获和非阻塞入队；后台完成格式化、实时发布和文件队列投递。 |
| `BoundedLogQueue` | 同时按事件数和估算字节数限制队列；满时立即丢弃，不反压业务线程。 |
| `RotatingLogFileWriter` | 文件后台 worker 单线程写入、严格大小检查、跨日/格式/大小轮转和当前文件状态。 |
| `RuntimeLogFormatter` | 编译受限模板，将日志事件稳定编码为单行 UTF-8 文本。 |
| `ShopMallLogAppender` 的实时阶段 | 在后台 worker 将结构化事件和已渲染文本写入内存缓冲区，不执行网络或磁盘 I/O。 |
| `LiveLogBuffer` | 分配启动内单调序号，保存有界事件并支持等待新事件。 |
| `LoggingSettingsService` | 读取默认/Redis 设置、校验、版本 CAS 更新和 Pub/Sub 发布。 |
| `RuntimeLoggingManager` | 将一个不可变设置快照应用到 Logback，并定期与 Redis 对账。 |
| `LogHistoryService` | 枚举合法日期/文件、安全读取活动或关闭文件、执行纯文本筛选。 |
| `AdminLogController` | 遵守项目控制器约定暴露管理端设置、实时和历史接口。 |
| `LoggingMetrics` | 记录写入、轮转、丢弃、实时连接、配置更新和文件错误指标。 |

### 4.1 异步链路与背压语义

业务线程进入 `ShopMallLogAppender.append()` 后只做固定上限的字段截取、参数引用估算和两级有界队列的非阻塞 `offer()`：

```text
业务线程
  -> inputQueue.offer（失败立即丢弃）
  -> formatter/live worker
       -> 参数展开、异常渲染、模板编码、LiveLogBuffer.append
       -> fileQueue.offer（失败立即丢弃）
  -> file worker
       -> 初始化目录、批量写入、flush、跨日/格式/10 MiB 轮转
```

参数 `toString()`、异常堆栈渲染、模板格式化、目录创建、文件 I/O、flush 和轮转都不在业务请求线程执行。输入队列和文件队列同时限制事件数与估算保留字节数；队列满、磁盘变慢或文件写入失败时只丢弃日志并递增指标，不等待、不向业务抛出异常。格式化 worker 和文件 worker 都是 daemon 线程，停机只在有界超时内排空，超时部分明确丢弃。

应用启动只启动后台线程，不等待日志目录或首个文件初始化；首个文件由文件 worker 在后台创建。因此 `fail-on-file-error=true` 仅让后台初始化错误进入 Logback Status、指标和管理端降级状态，并继续重试，不再阻止 Spring 应用启动。

## 5. 文件目录与命名

### 5.1 目录规则

日志根目录由 `shopmall.logging.storage-path` 指定，默认 `./logs`。每个文件按照其**打开时的本地日期**进入年、月、日三级目录：

```text
${LOG_STORAGE_PATH}/
  2026/
    08/
      08/
        20260808T192514.238-0.log
        20260808T192514.238-1.log
        20260808T221905.014-0.log
      09/
        20260809T000000.006-0.log
```

目录和文件时间均使用 `shopmall.logging.time-zone`，默认 `Asia/Shanghai`。业务/API 时间仍使用项目规定的 `LocalDateTime` 和 `ISO_LOCAL_DATE_TIME`。

### 5.2 文件名规则

文件名固定为：

```text
<file_time>-<x>.log
```

- `file_time` 使用 ISO-8601 基本本地日期时间 `yyyyMMdd'T'HHmmss.SSS`；
- `x` 是同一文件组内从 `0` 开始的非负轮转序号；
- 扩展名固定为 `.log`；
- 合法文件名正则为 `^\d{8}T\d{6}\.\d{3}-(0|[1-9]\d*)\.log$`。

ISO 标准扩展格式中的冒号不能用于 Windows 文件名，因此物理文件采用同属 ISO-8601 的基本格式。例如逻辑时间 `2026-08-08T19:25:14.238` 对应 `20260808T192514.238-0.log`。管理端 API 中的 `file_time` 仍返回 `2026-08-08T19:25:14.238`，不把文件名格式当作接口日期格式。

### 5.3 文件组与 `x`

“文件组”由一个 `file_time` 和一组连续 `x` 构成：

1. 应用每次启动都以当前 `LocalDateTime` 创建一个新文件组，首个文件为 `x = 0`；
2. 同一自然日内因大小或输出模板变化轮转时，保留 `file_time` 并递增 `x`；
3. 跨自然日后，以新日期中的首条事件时间创建新文件组，`x` 重置为 `0`；
4. 正常关闭和异常退出后都不再续写旧文件；下次启动生成新的 `file_time`；
5. 使用 `CREATE_NEW` 原子创建文件，若毫秒级时间发生极小概率碰撞，则重新取得下一个未占用的毫秒时间，禁止回退为追加旧文件。

因此，同一天内连续启动两次会得到两个不同的 `*-0.log`，而不是把第二次启动内容写入第一个文件。

### 5.4 轮转触发顺序

文件 worker 写入已经编码好的日志记录前，在 `RotatingLogFileWriter` 的状态锁内按以下顺序判断：

1. **日期变化**：关闭旧文件，在新日期目录创建新的 `file_time-0.log`；
2. **输出模板版本变化**：关闭当前文件，在同一文件组中创建 `file_time-(x+1).log`，保证一个文件只有一种格式；
3. **预计大小超限**：当 `current_size + record_size > 10 MiB` 时先轮转到 `x + 1`，再写记录；
4. 将完整记录写入当前文件并更新实际字节计数。

等级变化不需要轮转，因为它只决定后续哪些事件能够进入 appender，不改变已经写入记录的格式。

`10 MiB` 明确定义为 `10 * 1024 * 1024 = 10,485,760` 字节。单条记录的编码后大小限制为 `256 KiB`；超出时保留前部内容并追加 `...<truncated N bytes>`，因此单条记录不会破坏 10 MiB 上限。文件大小按 UTF-8 字节计算，不能按 Kotlin 字符数计算。

### 5.5 写入与关闭

- 每条事件固定写成一条物理行，行尾统一为 `\n`；
- message、MDC 和异常中的 `\r`、`\n`、反斜杠及控制字符按可见转义形式写入，防止日志注入和伪造新记录；
- appender 内部不得使用 SLF4J 记录自身错误，必须写入 Logback `StatusManager`，避免递归；
- JVM 正常关闭时在有限超时内刷新缓冲并关闭文件；异常退出最多损失操作系统尚未落盘的尾部缓冲，但已完成的行仍可读取；
- 日志根目录创建、首个文件初始化、写入、flush 和轮转都在文件 worker 执行。目录不可写或磁盘故障只让文件链路进入降级、重试并丢弃受影响事件，不阻塞业务线程；`fail-on-file-error=true` 不再让 Spring 启动等待或失败。

## 6. 自定义输出模板

### 6.1 采用受限模板而非任意 Logback Pattern

管理端不能直接提交任意 `PatternLayout` 表达式。任意 converter 会扩大资源消耗、暴露不应输出的内部属性，也难以保证历史日志“一条事件一行”。本方案提供一个受限模板语言，由 `RuntimeLogFormatter` 编译。

默认模板：

```text
{timestamp} [{level}] [{thread}] {logger} request_id={request_id} - {message}{exception}
```

支持的占位符：

| 占位符 | 内容 |
| --- | --- |
| `{timestamp}` | 事件本地时间，使用 `ISO_LOCAL_DATE_TIME`。 |
| `{level}` | `TRACE`、`DEBUG`、`INFO`、`WARN` 或 `ERROR`。 |
| `{thread}` | 线程名。 |
| `{logger}` | 完整 logger 名。 |
| `{message}` | SLF4J 参数展开后的消息。 |
| `{exception}` | 无异常时为空；有异常时输出转义后的异常类型、消息和堆栈。 |
| `{request_id}` | MDC 中的 `request_id`；不存在时为 `-`。 |

使用 `{{` 和 `}}` 输出字面花括号。模板不提供换行占位符；行尾由 formatter 统一追加。

### 6.2 模板校验

服务端是最终校验边界：

- UTF-8 字符长度为 `1..1024`；
- 不允许 NUL、CR、LF、ANSI ESC 或其他控制字符；
- 只能使用上表中的占位符；
- 必须包含 `{timestamp}`、`{level}`、`{logger}`、`{message}` 和 `{exception}`，避免格式调整后丢失关键诊断信息；
- 同一占位符禁止重复，避免无意义地放大格式化和响应成本；最终记录仍受 `256 KiB` 上限保护；
- 编译失败返回 `400`，不得写入 Redis，也不得改变当前 appender；
- 管理端保存前调用预览接口，服务端使用固定示例事件返回真实渲染结果。

模板成功更新后，`RuntimeLoggingManager` 原子替换内存中的已编译 formatter 和版本。已经进入输入队列的在途事件继续使用捕获时保存的 formatter 版本；文件 worker 在收到新版本记录时按 `format` 原因轮转，因此单个文件不会混用模板。模板编译和状态更新只操作内存，不执行磁盘或 Redis I/O。

## 7. 动态等级与设置存储

### 7.1 设置模型

```kotlin
data class LoggingSettings(
    val rootLevel: LogLevel,
    val loggerLevels: Map<String, LogLevel>,
    val outputTemplate: String,
    val version: Long,
    val source: LoggingSettingsSource,
    val updatedAt: LocalDateTime?,
    val updatedBy: Long?,
    val settingsId: String,
)
```

其中：

- 根等级支持 `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`OFF`；
- logger 名必须是合法 Java/Kotlin 包名或类名，不支持通配符，单项最多 200 字符；
- 最多 50 个 logger 覆盖项；
- 删除覆盖项表示恢复应用启动时捕获的显式等级；若启动时没有显式等级，则恢复为继承父 logger；
- `top.foxball.shopmall.logging.audit` 是固定 `INFO` 的受保护审计 logger，不能被根等级 `OFF` 或命名覆盖关闭。

### 7.2 Redis 数据

Redis Hash 使用键：

```text
logging:settings:v1
```

字段如下：

| 字段 | 示例 | 用途 |
| --- | --- | --- |
| `root_level` | `INFO` | 根 logger 等级。 |
| `logger_levels` | `{"org.hibernate":"WARN","top.foxball.shopmall.service":"DEBUG"}` | 按 logger 名排序后的规范 JSON。 |
| `output_template` | `{timestamp} [{level}] ...` | 已校验模板原文。 |
| `settings_id` | UUID | 区分 Redis 清空后重新创建的设置代际。 |
| `version` | `4` | 乐观锁版本。 |
| `updated_at` | `2026-08-08T19:25:14.238` | `LocalDateTime` 的 `ISO_LOCAL_DATE_TIME` 文本。 |
| `updated_by` | `42` | 修改设置的管理员 ID。 |

Hash 不设置 TTL。不存在时使用 `application.yaml` 的部署默认值，返回 `version = 0`、`source = DEFAULT`、`updated_at = null`、`updated_by = null`。第一次成功保存后写入完整快照。

更新使用 Redis Lua CAS：比较 `expected_version`，一次性写入完整设置、递增版本并发布 `logging:settings:changed` 消息。两个管理员同时保存时只允许一个成功，另一个返回 `409 Conflict` 并重新加载设置。

### 7.3 节点间刷新

每个节点按三种方式刷新：

1. 本节点处理成功的更新在返回 HTTP `200` 前完成内存快照切换；模板编译和 logger 等级调整不执行磁盘或 Redis I/O；
2. 其他节点的 Redis Pub/Sub listener 只把通知提交到专用 daemon `loggingTaskScheduler`，由后台任务合并突发通知、读取完整 Hash 并应用；
3. 每 5 秒由同一专用 daemon scheduler 执行一次轻量版本对账，补偿 Pub/Sub 在断线期间可能丢失的消息；Redis listener 线程不执行对账或模板编译。

Pub/Sub 只传版本提示，不直接传模板或 logger map，避免消息与持久快照不一致。应用快照时由 `RuntimeLoggingManager` 持有互斥锁，先校验和编译模板，再更新文件/实时 formatter，最后调整 logger 等级并发布本地生效版本。

### 7.4 失败语义

| 故障 | 行为 |
| --- | --- |
| Redis 在应用启动时不可用 | 文件日志继续使用部署默认设置；后台对账重试。业务启动是否依赖 Redis仍由现有应用策略决定。 |
| Redis 在设置读取/更新时不可用 | 管理接口返回 `503`；当前节点继续使用最后一个已成功应用的快照。 |
| Redis 中保存了非法设置 | 不应用非法值，保留最后有效快照，状态标记为 `DEGRADED` 并输出一次去重错误。 |
| 某节点应用新模板失败 | 该节点保留旧快照并进入 `DEGRADED`；周期对账继续重试，不能假装新版本已生效。 |
| Redis Hash 被清空 | 周期对账恢复部署默认值；`settings_id` 防止错误复用旧代际缓存。 |

每次成功修改通过受保护审计 logger 记录管理员 ID、旧/新根等级、logger 覆盖项差异、模板摘要、版本和 `LocalDateTime`。审计日志不得写 access token、Cookie、密码、密钥或完整模板中的潜在敏感字面量。

## 8. 实时日志

### 8.1 选择长轮询

本项目是 Spring MVC，管理端 access token 通过 `Authorization: Bearer` 发送，控制器响应统一使用 `ResponseBuilder`。因此本期采用长轮询，而不是原生 `EventSource`：

- 原生 `EventSource` 不能附加现有 Bearer 请求头；
- 长轮询可以直接复用 `useHttp` 的令牌刷新和统一错误处理；
- 每次响应仍是 `ResponseEntity<shared.Response>`，不破坏控制器约定；
- 日志到达时立即唤醒请求，空闲时最多等待 20 秒，用户感知仍是实时 tail。

不把 access token 放到 URL，也不额外签发流式连接票据。

### 8.2 内存事件模型

`ShopMallLogAppender` 的 formatter/live worker 为每条已通过 Logback 等级判断的事件生成：

```text
boot_id, sequence, timestamp, level, logger, thread,
request_id, message, exception, rendered, template_version
```

- `boot_id` 是每次应用启动新生成的 UUID；
- `sequence` 是当前启动内从 1 开始的 `AtomicLong`；
- `timestamp` 使用 `LocalDateTime`；
- `rendered` 与文件中写入的行内容一致，但不含行尾；
- 业务线程只短暂捕获最多 64 个参数引用和 Logback `IThrowableProxy` 引用，后台完成格式化后释放；实时缓冲区只保留结构化字段和渲染后的文本，不保留原始 Logback 事件。

缓冲区同时限制为最多 5,000 条和 16 MiB，按渲染文本、元数据和固定对象开销估算保留字节数，先触发哪个就淘汰最旧事件。实时响应还受 2 MiB 估算字节上限约束；第一条匹配事件即使单独超过该上限也允许返回一次，后续请求继续使用未推进的游标读取它。实时 UI 不是持久化存储；游标落后于最早事件时响应返回 `gap = true` 和 `dropped_count`，页面提示用户切换到历史日志补查。

### 8.3 长轮询语义

客户端携带 `boot_id` 和 `after_sequence`：

- 第一次未传游标时返回最新最多 200 条；
- 有符合过滤条件的新事件时立即返回；
- 没有新事件时最多等待 `wait_seconds`，然后返回空数组和最新游标；
- `boot_id` 不一致说明后端已重启，响应携带当前 `boot_id`、返回 `reset = true`，忽略旧 `after_sequence` 并从新缓冲区尾部开始；
- `after_sequence` 小于 `earliest_sequence - 1` 时返回 `gap = true`，从当前最早事件继续；`dropped_count` 等于旧游标与当前可读边界之间已经丢失的事件数；
- 第一次 tail 即使缓冲区已经从大于 1 的序号开始，也不报告 `gap`；游标恰好等于 `earliest_sequence - 1` 时同样不报告 `gap`；
- `next_sequence` 表示本次已检查到的位置。达到 `limit` 时指向最后检查事件，否则推进到请求时的最新序号；事件即使被过滤也会推进游标，避免重复扫描同一批数据。

支持的实时过滤只有：最低等级、logger 前缀和普通文本包含。文本最长 128 字符，不接受正则表达式。

实时接口可能在繁忙时快速返回，不能受当前全局 10 次/分钟限速。精确的、已认证的 `GET /admin/api/logs/live` 在普通全局限速器中排除，同时增加专用保护：每个管理员最多 2 个并发长轮询、全节点最多 50 个、`limit <= 500`、`wait_seconds <= 20`。其他日志接口仍受现有限速器保护；未认证或路径变体不会获得排除。

## 9. 历史日志

### 9.1 文件目录索引

`LogHistoryService` 只识别以下结构：

```text
<canonical-root>/<yyyy>/<MM>/<dd>/<yyyyMMdd'T'HHmmss.SSS>-<x>.log
```

接口不接受任意文件路径。客户端只传 ISO 日期、ISO `file_time` 和非负 `rotation_index`，服务端据此构造规范路径并再次验证：

- `file_time.toLocalDate()` 必须等于请求日期；
- `file_time` 必须是毫秒精度的 ISO `LocalDateTime`，微秒或纳秒精度请求直接拒绝；
- 规范化后的路径必须位于日志根目录内；
- 年/月/日目录和文件必须不是符号链接或 Windows reparse point；
- 文件名必须完全匹配合法正则；
- 只允许读取普通 `.log` 文件；未知文件和临时文件不对外暴露。

这样即使管理员构造恶意参数，也不能借历史日志接口读取 `.env`、密钥或系统文件。

### 9.2 内容分页

单个文件最大 10 MiB，历史内容按 UTF-8 字节偏移分页：

- 默认从文件尾部返回最近 200 条，最大 500 条或 1 MiB 响应正文；
- 后续请求传 `after_offset` 顺序读取，响应返回 `next_offset` 和 `eof`；
- 活动文件按打开读取通道时的大小形成一次快照，历史读取不获取 writer 锁，也不阻塞文件写入 worker；
- 尾部尚未写完且没有 `\n` 的记录暂不返回，下次请求再读取；
- `query` 只做大小写不敏感的普通字符串包含筛选；不解析管理员自定义模板，不提供结构化历史过滤；
- 文件在两次请求之间增长是合法的；若被保留策略删除，返回 `404` 并提示刷新文件列表。

列表响应包含 `date`、`file_time`、`rotation_index`、`filename`、`size_bytes`、`modified_at` 和 `active`。`modified_at`、`file_time` 都以 `LocalDateTime` 的 ISO 文本传输。

### 9.3 保留策略

为了防止磁盘无限增长，部署级默认保留 30 天：

- 当天计入保留天数，最早可读日期为 `today - retentionDays + 1`，该边界日期包含在内；
- 启动完成后和每天本地时间 03:15 扫描一次；
- 只删除日期早于保留边界的已关闭合法日志文件；
- 永不删除当前活动文件；
- 删除文件后自下而上清理空的日/月/年目录，但不得越过规范日志根目录；
- 历史读取使用 `Dispatchers.IO.limitedParallelism(4)`，避免大量管理员请求占满共享 I/O 线程；文件删除与目录清理在专用 daemon scheduler 中执行，并跳过正在读取的文件；
- UI 不提供删除按钮，保留天数只能通过部署配置调整并重启。

磁盘在保留任务执行前仍可能被突发日志写满，因此还应监控日志根目录可用空间。达到告警阈值只发告警，不在请求线程中临时删除日志。

## 10. 管理端 API

新增 `AdminLogController`，基路径为 `/admin/api/logs`。所有输入直接声明 `@RequestParam`，wire name 使用 snake_case；每个端点在方法内部声明自己的 `data class Response` 和条目类，直接构造 domain command、映射 service 结果及 `val rs = Response(...)`。不创建请求 wrapper、控制器级响应 DTO、mapper 或单次调用的私有辅助函数。

### 10.1 接口一览

| 方法与路径 | 用途 | 关键输入 |
| --- | --- | --- |
| `GET /admin/api/logs/settings` | 获取当前有效设置和文件运行状态 | 无 |
| `POST /admin/api/logs/settings/preview` | 校验并预览输出模板 | `output_template` |
| `PUT /admin/api/logs/settings` | 完整替换根等级、logger 覆盖和模板 | `root_level`、重复 `logger_override`、`output_template`、`expected_version` |
| `GET /admin/api/logs/live` | 长轮询实时日志 | `boot_id`、`after_sequence`、`minimum_level`、`logger_prefix`、`query`、`limit`、`wait_seconds` |
| `GET /admin/api/logs/history/dates` | 列出存在日志的日期及汇总 | `from_date`、`to_date` |
| `GET /admin/api/logs/history/files` | 列出指定日期文件 | `date`、`cursor`、`limit` |
| `GET /admin/api/logs/history/content` | 查看一个文件的历史内容 | `date`、`file_time`、`rotation_index`、`after_offset`、`tail`、`query`、`limit` |

所有服务方法都调用 `AdminAccessService.requireAdmin(adminId)`。除长轮询的专用限制外，接口继续受现有 `/admin/api/**` 角色保护和全局限速。

### 10.2 获取设置

```http
GET /admin/api/logs/settings
Authorization: Bearer <access-token>
```

```json
{
  "status": 200,
  "message": "OK",
  "data": {
    "root_level": "INFO",
    "logger_overrides": [
      { "logger_name": "top.foxball.shopmall.service", "level": "DEBUG" }
    ],
    "output_template": "{timestamp} [{level}] [{thread}] {logger} request_id={request_id} - {message}{exception}",
    "version": 3,
    "source": "REDIS",
    "updated_at": "2026-08-08T19:25:14.238",
    "updated_by": 42,
    "effective_version": 3,
    "runtime_status": "UP",
    "storage_path": "C:/var/shopmall/logs",
    "time_zone": "Asia/Shanghai",
    "max_file_size_bytes": 10485760,
    "active_file": "2026/08/08/20260808T192514.238-1.log",
    "active_file_size_bytes": 183204
  }
}
```

`storage_path` 仅用于只读诊断；UI 不允许编辑。生产环境如不希望暴露绝对路径，可只返回规范化后的显示别名和相对活动文件名。

### 10.3 预览模板

```http
POST /admin/api/logs/settings/preview?output_template={url-encoded-template}
Authorization: Bearer <access-token>
```

成功返回 `rendered` 和 `encoded_size_bytes`；非法模板返回统一 `400`。预览不读取或修改 Redis，也不改变当前日志文件。

### 10.4 更新设置

```http
PUT /admin/api/logs/settings?root_level=INFO&logger_override=org.hibernate=ERROR&logger_override=top.foxball.shopmall.service=DEBUG&output_template={url-encoded-template}&expected_version=3
Authorization: Bearer <access-token>
```

`logger_override` 每项使用 `<logger_name>=<LEVEL>`，服务层负责拆分和规范化；不传该参数表示清空全部命名覆盖。重复 logger、未知等级、非法名称、超过 50 项或模板非法均返回 `400`。更新是完整替换，成功后返回与读取接口相同的完整快照。

版本不匹配时：

```http
HTTP/1.1 409 Conflict
Content-Type: application/json;charset=UTF-8

{
  "status": 409,
  "message": "日志设置已被其他管理员更新，请重新加载后再保存",
  "data": { "actual_version": 4 }
}
```

### 10.5 实时日志响应

```json
{
  "status": 200,
  "message": "OK",
  "data": {
    "boot_id": "92f4ecde-d4f4-4d96-8a28-399cfa17f130",
    "reset": false,
    "gap": false,
    "dropped_count": 0,
    "earliest_sequence": 1201,
    "next_sequence": 1427,
    "events": [
      {
        "sequence": 1427,
        "timestamp": "2026-08-08T19:31:08.104",
        "level": "WARN",
        "logger": "top.foxball.shopmall.service.impl.OrderServiceImpl",
        "thread": "http-nio-8080-exec-4",
        "request_id": null,
        "rendered": "2026-08-08T19:31:08.104 [WARN] [...] ...",
        "template_version": 3
      }
    ]
  }
}
```

`wait_seconds` 默认 20，范围 `0..20`；`limit` 默认 200，范围 `1..500`。客户端必须保存响应中的 `boot_id` 和 `next_sequence`，并在下一次请求原样带回。`reset` 表示旧启动游标失效；`gap` 表示旧游标落后于内存保留边界；`dropped_count` 是这个断层中不可再从实时缓冲区读取的事件数。页面卸载或暂停时必须通过 `AbortController` 取消未完成请求。

## 11. 管理端 UI

新增以下文件：

| 文件 | 职责 |
| --- | --- |
| `AdminPanelUI/app/types/logging.ts` | 设置、实时事件、历史日期/文件/内容类型。 |
| `AdminPanelUI/app/composables/useLoggingApi.ts` | 设置、预览、长轮询和历史查询调用。 |
| `AdminPanelUI/app/pages/logs/index.vue` | 日志中心的实时、历史和设置三个视图。 |
| `AdminPanelUI/app/layouts/default.vue` | 使用 Lucide `ScrollText` 图标增加“日志中心”菜单项。 |

页面使用 `NTabs` 分为三个视图，不把整个页面包进卡片：

### 11.1 实时日志

- 顶部提供最低等级选择、logger 前缀输入、文本搜索、暂停/继续图标按钮、清空当前视图图标按钮和自动滚动开关；
- 清空只清理浏览器当前显示，不删除缓冲区或文件；
- 日志区域使用 Naive UI 虚拟列表和固定高度滚动容器，浏览器只保留最新 2,000 行及 4 MiB 字符预算；
- 等级颜色使用易区分的中性/蓝/黄/红组合，并保留文字等级，不能只依赖颜色；
- 自动滚动仅在用户位于底部时生效；用户向上查看时不得强制跳回底部；
- `gap = true`、后端重启、网络重连和 `template_version` 变化分别显示简短状态条；
- 每次长轮询完成后立即发起下一次；网络错误依次等待 500 ms、1 s、2 s、5 s，后续失败保持 5 s 上限，成功后重置；
- 收到 `401/403` 仍走现有 `useHttp` 的 access token 刷新和登录失效流程。

### 11.2 历史日志

- 日期选择器只允许选择后端返回的可用日期；
- 文件列表展示开始时间、轮转序号、大小、最后修改时间和“写入中”状态；
- 内容区支持从尾部打开、继续向前/后加载和普通文本搜索；
- 内容区使用 Naive UI 虚拟列表和固定高度滚动容器；超长行保留原始空白并通过横向滚动查看，最多保留 10,000 行及 8 MiB 字符预算；
- 活动文件可以手动刷新；历史页面不自动高频轮询；
- 不提供任意路径输入、删除按钮或前端直接访问文件系统 URL。

### 11.3 日志设置

- 根日志等级使用 `NSelect`；
- logger 覆盖项使用可增删的紧凑表格，每行包含 logger 名和等级选择；
- 输出模板使用等宽文本输入，旁边显示服务端预览结果和编码字节数；
- 保存按钮一次提交完整快照并携带 `expected_version`；
- 收到 `409` 时丢弃旧基线、重新加载并提示管理员重新确认；
- 展示配置来源、版本、最后修改时间/管理员、运行状态、当前文件和文件大小；
- 路径、时区、10 MiB 上限和保留天数只读显示。

前后端所有新时间字段均传输 ISO-8601 字符串；`LocalDateTime` 使用不带 offset 的 `ISO_LOCAL_DATE_TIME`，不传 epoch。

## 12. 安全与隐私

1. 所有接口同时经过 Spring Security `ROLE_ADMIN` 和服务层 `AdminAccessService` 校验。
2. 日志等级和模板更新使用版本 CAS，防止管理员页面互相覆盖。
3. 管理端不能修改根路径、文件大小、保留策略或传入任意文件路径。
4. 历史读取执行规范根目录、文件名、普通文件和无符号链接检查，防止目录穿越。
5. 模板使用占位符白名单，不执行任意 Logback converter、表达式或脚本。
6. message 和异常中的换行/控制字符被转义，防止伪造日志行。
7. 文本搜索不是正则，长度、结果数、读取字节数和并发连接都有硬上限。
8. 业务代码仍必须遵守“不得记录密码、JWT、Authorization、Cookie、支付密钥、Webhook 密钥和完整个人隐私数据”的规则；日志系统不是秘密扫描器。
9. 管理员设置修改写入不可关闭的审计 logger；长轮询本身不逐次写审计日志，避免读取日志产生新的无限日志反馈。
10. 实时事件和历史响应设置 `Cache-Control: no-store`，反向代理不得缓存。

## 13. 可靠性与多实例约束

### 13.1 关键故障隔离

- 文件 appender、实时 appender 和控制台相互独立；实时缓冲区故障不能阻止文件写入；
- Redis 只承担控制面设置，不在每条日志的写入路径上；Redis 故障不能停止本地日志；
- 文件 I/O 错误通过 Logback Status、Micrometer 和管理端运行状态暴露，不能在 appender 内递归记录；
- 日志写入不抛出异常到业务请求，但生产监控应把持续文件写入失败视为高优先级告警；
- `RuntimeLoggingManager` 保存最后有效设置，非法或暂时不可用的新设置不能破坏现有日志输出。

### 13.2 多实例

运行时等级和模板通过 Redis 在所有节点间同步，但实时缓冲区和历史文件属于当前节点。多实例部署必须满足以下之一：

1. 每个节点使用不同的 `LOG_STORAGE_PATH`，管理端通过节点专用地址或粘性路由查看该节点；
2. 在共享存储中为每个节点配置独立根目录，例如 `/logs/node-a`、`/logs/node-b`，禁止多个进程写同一个根目录；
3. 若需要一个页面检索所有节点，应另行接入 Loki/OpenSearch 等集中日志系统，不把 Redis 当作日志正文总线。

每个设置响应和实时响应应包含部署配置的 `instance_id`。本期不实现跨节点日志正文聚合，文档和 UI 必须明确当前查看的是哪个实例。

## 14. 配置建议

在 `application.yaml` 增加：

```yaml
shopmall:
  logging:
    storage-path: "${LOG_STORAGE_PATH:./logs}"
    time-zone: "${LOG_TIME_ZONE:Asia/Shanghai}"
    instance-id: "${LOG_INSTANCE_ID:local}"
    # 固定满足需求；启动校验必须等于 10 MiB。
    max-file-size: 10MB
    max-record-size: 256KB
    retention-days: "${LOG_RETENTION_DAYS:30}"
    live-buffer-events: "${LOG_LIVE_BUFFER_EVENTS:5000}"
    live-buffer-bytes: "${LOG_LIVE_BUFFER_BYTES:16777216}"
    live-response-bytes: "${LOG_LIVE_RESPONSE_BYTES:2097152}"
    live-batch-window-millis: "${LOG_LIVE_BATCH_WINDOW_MILLIS:100}"
    live-max-wait-seconds: 20
    async-queue-events: "${LOG_ASYNC_QUEUE_EVENTS:4096}"
    async-queue-bytes: "${LOG_ASYNC_QUEUE_BYTES:33554432}"
    file-queue-events: "${LOG_FILE_QUEUE_EVENTS:4096}"
    file-queue-bytes: "${LOG_FILE_QUEUE_BYTES:33554432}"
    file-batch-events: "${LOG_FILE_BATCH_EVENTS:128}"
    file-flush-interval-millis: "${LOG_FILE_FLUSH_INTERVAL_MILLIS:100}"
    file-failure-backoff-millis: "${LOG_FILE_FAILURE_BACKOFF_MILLIS:1000}"
    shutdown-timeout-millis: "${LOG_SHUTDOWN_TIMEOUT_MILLIS:5000}"
    reconcile-interval-millis: "${LOG_RECONCILE_INTERVAL_MILLIS:5000}"
    default-root-level: "${LOG_ROOT_LEVEL:INFO}"
    default-output-template: "{timestamp} [{level}] [{thread}] {logger} request_id={request_id} - {message}{exception}"
```

同时：

- 在 `.env.example` 增加对应部署变量；
- 在 `.gitignore` 增加 `/logs/`；
- 生产将 `LOG_STORAGE_PATH` 指向持久化卷并限制目录权限；
- 生产保持现有 `REDIS_CLEAR_ON_STARTUP=false`，否则运行时设置会恢复默认值；
- `LoggingProperties` 启动时校验根路径不为空、时区有效、记录上限小于文件上限、缓冲区边界合理、默认等级和模板合法。

## 15. 可观测性

建议增加以下低基数 Micrometer 指标：

```text
shopmall.logging.events{level,outcome=written|truncated|failed}
shopmall.logging.bytes_written
shopmall.logging.rotations{reason=startup|date|size|format}
shopmall.logging.active_file_size_bytes
shopmall.logging.live_buffer_events
shopmall.logging.live_buffer_bytes
shopmall.logging.live_events_evicted
shopmall.logging.live_requests_active
shopmall.logging.settings_updates{outcome=success|conflict|failed}
shopmall.logging.runtime_settings_version
shopmall.logging.file_errors
shopmall.logging.history_reads{outcome=success|not_found|rejected}
```

指标不能使用 logger 名、管理员 ID、文件名或查询文本作为 tag。现有系统状态接口可增加一个 `logging` 节点，返回 `UP/DEGRADED`、当前设置版本、活动文件相对路径、活动文件大小、最后成功写入时间和最后错误摘要。

## 16. 测试计划

### 16.1 文件 appender

| 场景 | 预期 |
| --- | --- |
| 首次启动 | 创建当天目录和 `file_time-0.log`。 |
| 同一天再次启动 | 创建不同 `file_time-0.log`，旧文件字节不变。 |
| 毫秒文件名碰撞 | 使用 `CREATE_NEW` 选择新时间，不追加现有文件。 |
| 预计写入超过 10 MiB | 写入前轮转到 `x + 1`，两个文件均不超过上限。 |
| 单条超长事件 | 在 256 KiB 内截断并带截断标记。 |
| 跨午夜 | 旧文件关闭，新事件进入新年月日目录和新的 `-0.log`。 |
| 模板变更 | 当前文件关闭，`x + 1` 使用新模板；旧文件格式不变。 |
| 等级变更 | 不轮转文件，后续事件按新等级进入或被过滤。 |
| 并发日志 | 记录不交叉、不丢半行、`x` 不重复、字节计数准确。 |
| 非 ASCII 内容 | UTF-8 内容正确，大小按字节判断。 |
| 正常关闭 | 尾部刷新并关闭，可立即被历史服务完整读取。 |

使用 `@TempDir` 和可注入 `Clock`/`ZoneId` 测试，不用真实等待跨日。单位测试可将内部大小阈值注入为较小值；另保留一个真实 10 MiB 的集成测试验证生产配置。

### 16.2 设置与 Logback

| 场景 | 预期 |
| --- | --- |
| Redis 无设置 | 使用部署默认值，版本 0。 |
| 合法完整更新 | CAS 成功、版本递增、本节点立即生效、发布通知。 |
| 两管理员同版本更新 | 仅一个成功，另一个返回 `409`。 |
| Pub/Sub 丢失 | 5 秒对账最终应用新版本。 |
| Redis 清空后重建 | `settings_id` 变化，节点不复用旧快照。 |
| 非法 logger/等级/模板 | 返回 `400`，Redis 和当前 LoggerContext 不变。 |
| 删除 logger 覆盖 | 恢复启动基线或继承等级。 |
| 根等级设为 `OFF` | 普通日志关闭，受保护审计 logger 仍记录设置变更。 |
| Redis 故障 | 设置接口 `503`，文件日志继续使用最后有效快照。 |

Redis CAS、发布订阅和恢复测试使用现有 Testcontainers 模式，不能只 mock 调用顺序。

### 16.3 实时与历史接口

| 场景 | 预期 |
| --- | --- |
| 非管理员访问 | Spring Security 返回 `401/403`。 |
| 无新日志长轮询 | 在超时边界返回空事件和当前游标。 |
| 新日志到达 | 等待请求立即被唤醒。 |
| 缓冲区溢出 | 返回 `gap=true` 和准确的丢弃数量。 |
| 后端重启 | `boot_id` 变化并返回 `reset=true`。 |
| 首次 tail/边界游标 | 首次请求返回最新 `limit` 条且不误报断层；`earliest_sequence - 1` 是无断层边界。 |
| 等级/logger/文本过滤 | 返回匹配项，过滤掉的事件也正确推进 `next_sequence`。 |
| 并发连接超限 | 返回 `429`，不占用额外等待线程。 |
| 并发连接释放 | 正常完成、动作异常和超限拒绝后都归还节点/管理员许可。 |
| 历史保留边界 | `today - retentionDays + 1` 可读，更早日期和未来日期返回 `400`。 |
| 历史标识精度 | `file_time` 只接受毫秒精度，负数轮转序号返回 `400`。 |
| 历史路径穿越/符号链接 | 非法标识返回 `400`，链接目录或文件按不存在返回 `404`，绝不读取根目录外文件。 |
| 活动文件读取 | 只返回完整行，后续增长可从游标继续。 |
| 文件轮转期间读取 | 旧文件稳定可读，新活动文件出现在刷新后的列表。 |
| 文件被保留任务删除 | 后续内容请求返回 `404`。 |
| 超大 limit/query | 参数校验返回 `400`。 |

### 16.4 管理端

- 保存成功后设置基线、版本和运行状态同步更新；
- `409` 后重新加载且不静默覆盖；
- 页面卸载、暂停和筛选变化时取消旧长轮询；
- 自动滚动不打断用户查看旧行；
- 2,000 行有界列表和固定滚动容器不会造成页面持续增内存；
- 后端重启、游标断层、500 ms/1 s/2 s/5 s 网络退避、模板版本提示和 token 刷新状态可恢复；
- 窄屏下工具栏换行，日志文本不遮挡其他控件。

## 17. 实施顺序

1. 增加 `LoggingProperties`、模板编译器和 `ShopMallLogAppender` 两级有界异步队列，先完成命名、跨日、10 MiB 轮转和启动新文件测试。
2. 增加 Redis 设置模型、CAS 脚本、Pub/Sub、周期对账和 `RuntimeLoggingManager`，完成动态等级/模板与审计。
3. 在 `ShopMallLogAppender` 的后台格式化阶段接入 `LiveLogBuffer`、长轮询及专用连接限制，并为精确实时 GET 路径配置固定限速排除。
4. 增加安全文件目录索引、历史内容分页和保留任务。
5. 按控制器约定实现 `AdminLogController`，补齐统一响应、参数验证、错误映射与集成测试。
6. 新增 `AdminPanelUI` 日志中心页面、API composable、类型和菜单入口，完成实时、历史、设置三个视图。
7. 增加指标、系统状态和生产告警，验证持久化卷、目录权限、磁盘容量和多实例根目录隔离。

## 18. 验收标准

- 连续启动应用两次，产生两个不同的 `*-0.log`，第二次不修改第一次文件；
- 日志路径严格为 `storage/yyyy/MM/dd/file_time-x.log`，文件名使用跨平台安全 ISO-8601 基本格式；
- 当前文件预计超过 10 MiB 前自动切换到 `x + 1`，不覆盖或重命名历史文件；
- 跨日后的首条日志进入新日期目录和新的 `-0.log`；
- 管理员保存根等级或 logger 覆盖后，后续日志无需重启即按新等级输出；
- 管理员保存合法模板后，后续文件和实时日志使用新格式，非法模板不能影响当前输出；
- 实时页面可以持续 tail、暂停、恢复、过滤，并能识别缓冲区断层和后端重启；
- 历史页面可以按日期和文件读取活动/关闭日志，且无法访问日志根目录外的任何文件；
- 非管理员无法读取日志或修改设置；并发管理员更新不会互相静默覆盖；
- Redis 故障不阻止本地日志写入，文件故障有明确状态和指标；
- 全部新接口遵守项目 controller、snake_case、统一响应和 `LocalDateTime` 约定；
- 不创建或修改数据库迁移脚本。
