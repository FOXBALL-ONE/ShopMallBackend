# 文件模块设计：本地 + S3 · 签名下载 · 反刷防护 · 元数据高并发查询

> 背景：跨境泳装独立站，沿用项目 lean 风格与既有模式。
>
> **现状基线**：文件模块骨架**已存在**且可用 ——
> `controller/FileController.kt`（上传/列表/批量链接/签名下载/删除/批量删除）、
> `service/impl/FileServiceImpl.kt`（本地存储、SHA-256、路径穿越防护、删除前 staging 恢复）、
> `service/FileLinkSigner.kt`（HMAC-SHA256 短期签名，绑定 fileId+userId+expires）、
> `entity/jdbc/StoredFile.kt`（`file_metadata` 表，裸 `ownerId` 去归一化）、
> `config/FileProperties.kt` / `config/FileConfig.kt`、
> `repository/StoredFileRepository.kt`。
> `config/SecurityConfig.kt` 已预留 `GET /api/files/*/download` `permitAll`、`/api/files/**` `authenticated`；
> `application.yaml` 已预留 `shopmall.file.*`（storagePath / baseUrl / signingSecret / downloadTokenTtl / maxBatch / maxFileSizeBytes）。
>
> 本文设计是对现有骨架的**增强与补全**，补齐用户要求的能力缺口：**反刷/慢刷/速率控制/反遍历**、**S3 存储**、**可选签名（仅限特定用户）**、**元数据高并发查询优化**。不推翻现有实现，而是沿既有包结构与模式扩展。

---

## 一、要解决的核心问题

现有骨架已覆盖上传/下载/删除/签名的基础链路，但存在五个缺口：

1. **无反刷 / 无速率控制**：上传、下载、链接刷新、删除均无频次限制。低俗慢刷（低速率长时间拉满）与高频爆破（短时间洪峰）都无法拦截，磁盘/带宽/DB 易被廉价耗尽。
2. **无反遍历**：现有签名下载链接形式固定（`/api/files/{uuid}/download?userId=...&expires=...&signature=...`），且文件存储路径用 `LocalDate.now()` 分桶（可枚举的日期目录）。UUID 虽不可猜，但**元数据 ID 一旦泄漏即无频次防护**，且签名链接的 `userId` 参数可被枚举尝试。
3. **仅本地存储**：`FileServiceImpl` 硬编码本地文件系统，无 S3 支持。跨境业务图片量大、需 CDN 加速与跨区冗余，本地存储不可扩展。
4. **签名语义单一**：现有 `FileLinkSigner` 签名固定绑定 `userId`，下载端点用 `@RequestParam userId` 接收。无法表达「仅限特定用户/角色访问」（如某文件只给 ADMIN、或只给购买该订单的用户）。`downloadTokenTtlSeconds` 也无「按文件敏感度分级 TTL」。
5. **元数据查询无高并发优化**：`StoredFileRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)` **一次性全量加载某用户全部文件**，无分页、无缓存、无索引利用评估；列表场景下随用户文件增长会成为热点慢查询。签名下载每次也要 `findByIdAndOwnerId` 落库。

### 设计目标

- **反刷三件套**：高频令牌桶限流 + 低俗慢刷异常检测 + 全局配额。覆盖上传/下载/链接刷新/删除四个写与读热点。
- **反遍历**：签名载荷纳入不可枚举的 nonce + 文件 ID 用 CSPRNG UUID（已有）+ 下载链接一次性/短 TTL + 下载计数异常告警。
- **双存储后端**：`StorageBackend` 抽象（`LocalStorageBackend` / `S3StorageBackend`），按文件粒度选择后端（本地热数据 / S3 冷数据与 CDN），配置切换。
- **可选签名**：签名载荷可绑定「用户 ID」或「角色/标签」（如 `admin`、`order:{orderNo}`），下载端点按签名声明鉴权；可选不签名（公开）。
- **元数据高并发查询**：分页 + 复合索引 + Redis 元数据缓存 + 下载鉴权短路径缓存，消除热点全量加载与重复落库。

### 关键决策

| 维度 | 选择 | 理由 |
|---|---|---|
| 限流 | Redis Lua 令牌桶（per user + per IP + per file） | 复用既有 `StringRedisTemplate` + Lua 模式（对齐 `order_idempotency.lua`），无新中间件；令牌桶天然支持突发+平均速率 |
| 慢刷检测 | 滑动窗口计数 + 异常告警（不阻断） | 慢刷用阈值拦截会误伤正常用户；记录 + 告警 + 软封禁（降级配额）更稳妥 |
| 反遍历 | 签名 nonce + 短 TTL + 下载计数 | 签名已防篡改；nonce + 短 TTL 防 URL 复用与重放；下载计数异常（单文件短时高频访问）触发告警 |
| 存储 | `StorageBackend` 接口 + 本地/S3 双实现 | 抽象隔离 IO 细节；按文件 `storage` 字段路由；S3 用预签名 URL 卸载带宽到 CDN |
| 签名 | 扩展 `FileLinkSigner` 载荷：`scope` 字段 | 单一密钥多 scope，`scope` 表达「user:123」「role:admin」「order:ORD123」「public」 |
| 元数据查询 | 分页 + 复合索引 + Redis 缓存 | DB 是真相源；缓存兜住热点读；分页消除全量加载 |

> **不引入** Redisson、Kafka、Bucket4j。限流用 Redis Lua 自实现，与 `OrderIdempotencyService` 同源；事件无需 outbox（文件无跨服务最终一致诉求）。

---

## 二、整体架构

```
客户端
  │  POST /api/files            (multipart, JWT)
  │  POST /api/files/batch/links (JSON, JWT, 可选 scope)
  │  GET  /api/files/mine?page=&size=  (JWT, 分页)
  │  GET  /api/files/{fileId}/download?scope=&expires=&nonce=&signature=  (permitAll, 验签)
  │  GET  /api/files/{fileId}/secure-download?scope=&expires=&nonce=&signature=  (JWT, role:/order: scope)
  │  DELETE /api/files/{fileId} / /api/files/batch (JWT)
  ▼
FileController ──► FileService.*（ownerId 隔离）
  │   ① 反刷前置（FileRateLimitService，Redis Lua 令牌桶，异步多桶）：
  │      a. 上传：user:upload ∧ ip:upload 双桶（双桶「与」门，任一耗尽即 429）
  │      b. 下载验签：ip:download 单桶 + per-file 下载计数（反遍历）
  │      c. 链接刷新/删除：user:link / user:delete 单桶
  │      d. 慢刷：仅 INCR 滑动窗口计数（同步、O(1)），阈值判定与软封禁写入异步（@Async，不阻塞热点）
  │   ② 存储校验：ContentType 白名单 + 魔数嗅探（反可执行/脚本上传）
  │   ③ 存储：StorageBackend.store(stream, length, ext, ownerKey) → 写本地或 S3
  │   ④ 元数据：StoredFile 落库（file_metadata 表），写后写穿 Redis 缓存
  │   ⑤ 签名：FileLinkSigner.sign(fileId, scope, ttl) → 返回 URL（ttl 按 scope 敏感度分级）
  ▼
[StorageBackend] LocalStorageBackend / S3StorageBackend（按 file.storage 路由）
[FileRateLimitService] Redis Lua 令牌桶（同步取令牌）+ 慢刷计数（同步 INCR）
[FileSlowBurnDetector] @Async 判定阈值 → 写软封禁 + 告警（不阻塞请求线程）
[FileMetaCache] Redis 元数据缓存（写穿 + TTL + 单飞防击穿）
[FileStorageReconciler] @Scheduled S3 孤儿对账（选主）
```

> **下载路径分流**：
> - `permitAll` 的 `/download` 端点仅服务 `user:` / `public` scope（签名即凭证，无 JWT）。
> - `authenticated` 的 `/secure-download` 端点服务 `role:` / `order:` scope（签名 + JWT 双因子，JWT 验身份、签名验「已获签发」）。
> 两条路径共用 `FileService.openSignedDownload`，差异仅在控制器层是否从 `@AuthenticationPrincipal` 取 userId 参与 scope 校验。

### 防护矩阵

| 风险 | 防护机制 |
|---|---|
| 高频上传刷盘 | per-user + per-IP 令牌桶（Redis Lua 原子取令牌），超限 429 + Retry-After |
| 低俗慢刷（低速率长时段） | 滑动窗口累计计数 + 异常告警 + 软封禁（配额降级，不阻断） |
| 下载链接遍历/重放 | 签名 nonce（每次签发随机）+ 短 TTL + per-file 下载计数告警 |
| 下载链接伪造 | HMAC-SHA256 绑定 fileId+scope+expires+nonce，常量时间比较（已有 `FileLinkSigner` 模式） |
| 恶意内容上传 | ContentType 白名单 + 魔数嗅探双层校验 + 下载侧 attachment/nosniff（§4.4） |
| S3 预签名 URL 内联 XSS | 预签名强制 `response-content-disposition=attachment` + `response-content-type`（§3.3/§4.4） |
| 元数据枚举 | 文件 ID 用 CSPRNG UUID（已有），不可枚举；列表按 owner 分页，不暴露他人 ID |
| 路径穿越 | 现有 `resolveStoredPath` 已做 `resolved.startsWith(storageRoot)` 边界校验（保留） |
| 删除竞态 | 现有 staging 恢复机制保留（移动到 `.deleting/` 再删 DB，失败回滚内容） |
| 大文件耗内存 | 流式写 + SHA-256 边读边算（已有 `writeAndHash`），S3 用分片上传 |
| 元数据热点查询 | 分页 + 复合索引 + Redis 缓存 + singleflight 防击穿 + 空值标记防穿透（§6） |
| 缓存击穿/穿透 | singleflight 单飞回填 + 不存在 fileId 写空值标记短 TTL（§6.3） |
| Redis 故障 | 逐功能降级：限流 fail-open、缓存 fail-through、计数 fail-skip，不冒泡 500（§4.5） |

---

## 三、存储后端抽象

### 3.1 `StorageBackend` 接口 — `shared/StorageBackend.kt`（新增）

> 抽象存储 IO，隔离本地与 S3。`FileServiceImpl` 不再直接操作 `java.nio.file`，改为委托 backend。

```kotlin
interface StorageBackend {
    /** 存储一份文件，返回受控相对路径/key + sha256 + 实际字节数。流式写，边写边算 digest。 */
    fun store(input: InputStream, length: Long, suggestedExt: String, ownerKey: String): StoredBlob

    /** 打开读取（下载用）。本地返回流，S3 返回预签名 URL 委托 CDN。调用方按 [BlobContent] 类型决定流式输出或 302。 */
    fun open(storedKey: String): BlobContent

    /** 删除一份内容。幂等（不存在视为成功）。 */
    fun delete(storedKey: String)

    /** 是否存在（对账/迁移用，非热点）。本地 isRegularFile，S3 headObject。 */
    fun exists(storedKey: String): Boolean

    /** 后端标识，落库到 StoredFile.storage 字段。 */
    val id: String   // "local" / "s3"
}

data class StoredBlob(val storedKey: String, val sha256: String, val sizeBytes: Long)

/**
 * 下载内容形态。控制器据子类型分支：Stream → 流式输出（应用经手字节），
 * Redirect → 302 到 S3/CDN 预签名 URL（应用不经手字节，带宽卸载）。
 */
sealed interface BlobContent {
    /** 本地：返回流。调用方负责在使用后关闭流（见下注）。 */
    data class Stream(val stream: InputStream, val size: Long, val contentType: String?) : BlobContent
    /** S3：返回预签名 URL。expiresAt 是 S3 预签名失效时间（与上层应用签名 expires 解耦）。 */
    data class Redirect(val presignedUrl: String, val expiresAt: LocalDateTime,
                        val contentType: String?, val size: Long) : BlobContent
}
```

> **本地流的资源管理**：`BlobContent.Stream.stream` 的关闭责任在控制器。`FileController.download` 用 `FileSystemResource` 已自动管理流关闭（Spring `Resource` 契约）；若改为直接 `InputStream`，必须 `use {}` 或交由 `Resource` 封装，**不得让流泄漏到响应外**。本地下载走 `FileSystemResource(downloadable.path)`（现状如此），保留。

> **双重 TTL 关系**（关键，易混）：
> - **应用签名 TTL**（`download-token-ttl-seconds`）：`FileLinkSigner` 签名的 `expires`，控制「签名链接本身的有效期」。下载端点先验这个。
> - **S3 预签名 TTL**（`s3.presign-ttl-seconds`）：`BlobContent.Redirect.presignedUrl` 的失效时间，控制「S3 预签名 URL 的有效期」。
> - **约束**：S3 预签名 TTL **≤** 应用签名 TTL（否则应用签名已过期但 S3 URL 仍可用，鉴权失效）。默认两者都 300s，但语义独立——应用签名是「这组参数签名有效」，S3 presign 是「这个 S3 URL 有效」。下载 302 时，前端拿到 S3 URL 后，应用签名过期不影响已发出的 S3 URL（已在浏览器侧），可接受。

### 3.2 `LocalStorageBackend` — `shared/LocalStorageBackend.kt`（新增）

封装现有 `FileServiceImpl` 的本地逻辑：日期分桶（`yyyy/MM/dd`，UTC）+ CSPRNG UUID 文件名 + `CREATE_NEW` 防覆盖 + 流式 SHA-256 + 路径穿越防护（`resolved.startsWith(storageRoot)`）。**`storageRoot` 边界校验逻辑从 `FileServiceImpl` 上移到 backend 内部**，`FileServiceImpl` 不再直接拼路径。
- `store`：`ownerKey` 用作目录分桶前缀（`{ownerKey}/yyyy/MM/dd/{uuid}.{ext}`），便于按 owner 物理隔离与后续按 owner 迁移/清理。
- `open`：返回 `BlobContent.Stream`，包装 `FileSystemResource` 的输入流 + size + contentType。
- `exists`：`Files.isRegularFile(path)`。
- **磁盘写入与 SHA-256 原子性**：流式写盘与摘要计算在同一循环（现有 `writeAndHash`），写完即得 sha256，无需二次读盘。

### 3.3 `S3StorageBackend` — `shared/S3StorageBackend.kt`（新增）

- 依赖 AWS SDK v2 `S3Client` + `S3Presigner`（`software.amazon.awssdk:s3`），bean 由 `S3Config` 装配（region/endpoint/bucket/accessKey/secretKey 来自 `shopmall.file.s3.*`）。`S3Config` 用 `@ConditionalOnProperty(name=["shopmall.file.default-backend"], havingValue="s3")` 或显式开关 `shopmall.file.s3.enabled`，**本地模式下不强制 S3 配置**（无 region/bucket 时不创建 bean，`LocalStorageBackend` 唯一存在）。
- `store`：`PutObjectRequest` + 流式上传；大文件（超阈值，如 50MB）走 `S3TransferManager` 分片上传。key 用 `CSPRNG-UUID` + 扩展名，前缀按 `ownerKey/yyyy/MM/dd/` 分桶。**上传时设对象元数据 `Content-Type` + `Content-Disposition: attachment`**（即便预签名时不带覆盖参数，对象默认也是附件下载，防内联）。
- `open`：返回 `BlobContent.Redirect`，`presignedUrl` 用 `S3Presigner` 生成，TTL = `s3.presign-ttl-seconds`。**预签名时显式设 `ResponseContentDisposition("attachment; filename=...")` + `ResponseContentType`**，覆盖对象默认值，确保浏览器附件下载（§4.4 防 SVG/HTML 内联 XSS）。
- `delete`：`DeleteObjectRequest`，幂等（S3 删除不存在对象返回 204，不报错）。
- `exists`：`HeadObjectRequest`，404 → false。
- **元数据一致性**：S3 上传成功但 DB 落库失败 → 孤儿对象。用现有 `FileServiceImpl.upload` 的 try-catch 回滚模式（存储成功后落库失败 → `backend.delete(storedKey)` 清理），S3 删除失败仅记审计（S3 无事务，孤儿由 `@Scheduled` 对账清理，见 §7）。
- **S3 异常映射**：`S3Exception`（限流 `SlowDown`/`ServiceUnavailable`）→ `FileStorageException`（503 + Retry-After），不冒泡 500；客户端可重试上传。

### 3.4 后端选择策略

`StoredFile.storage` 字段（新增，`length=16`，值 `local` / `s3`）记录实际后端。选择策略：
- **配置默认后端**：`shopmall.file.default-backend`（`local` / `s3`），全量切换。
- **按文件粒度覆盖**（后续）：可按 `contentType` 或 size 路由（如图片走 S3+CDN，临时文件走本地）。本期实现配置级默认 + 字段留存，路由逻辑预留扩展点。

> 本地→S3 迁移：现有本地文件可通过 `@Scheduled` 对账任务（扫 `storage=local` 且超龄的）异步 `store` 到 S3 再更新 `storage` 字段。本期不实现，仅留字段与扩展点。

### 3.5 S3 下载链路鉴权时序（重要）

S3 走 302 重定向后，鉴权时序与本地不同，必须讲清，否则反遍历逻辑悬空：

```
浏览器 GET /api/files/{id}/download?scope=user:1&expires=...&nonce=...&signature=...
   │  ① 应用验签（FileLinkSigner.isValid）—— 此时鉴权完成
   │  ② IP 令牌桶取令牌 + file:dc 计数 INCR（反遍历计数点，在 302 之前）
   │  ③ 元数据缓存取 storedKey/storage/ownerId（Redis）
   │  ④ scope 校验（user:1 命中）
   │  ⑤ backend.open(storedKey) → S3StorageBackend 返回 BlobContent.Redirect(presignedUrl)
   ▼
应用 302 Location: https://s3.../...?X-Amz-Signature=...   （带 response-content-disposition=attachment）
   ▼
浏览器跟随 302 → 直连 S3/CDN 下载字节（应用不经手）
```

**关键点**：
- **鉴权只发生在①**：应用验签通过即放行 302。S3 预签名 URL 本身是「应用签发的下载凭证」，任何拿到它的人都能在 presign TTL 内下载——所以**应用签名 TTL 必须 ≤** 用户期望的下载窗口，且 S3 presign TTL **≤** 应用签名 TTL（§3.1 约束）。
- **S3 URL 泄漏窗口**：presign TTL（默认 300s）内，URL 泄漏即可被滥用。缓解：短 presign TTL + `file:dc` 计数告警（计数点在 302 之前，反映「签名链接被兑换为 S3 URL 的次数」）。
- **不可撤销已签发的 S3 URL**：一旦 302 发出，S3 URL 在 presign TTL 内有效，应用无法吊销（S3 预签名不经过应用）。唯一吊销手段是删除 S3 对象（`backend.delete`），但会误伤正常下载。故高敏文件**用更短的 presign TTL**（§5.4 分级 TTL），而非靠吊销。

> 这条时序决定了：**S3 模式下「下载鉴权」是一次性发生在应用层的，S3 URL 是其产物**。本地模式下鉴权与字节输出在同一请求内，无此产物泄漏问题。两者反遍历计数点一致（都在应用验签后、内容交付前），告警语义统一。

---

## 四、反刷与速率控制（核心）

### 4.1 `FileRateLimitService` — `shared/FileRateLimitService.kt`（新增）

> Redis Lua 令牌桶，复用 `StringRedisTemplate` + `DefaultRedisScript`（对齐 `OrderIdempotencyService` 的 Lua 模式）。脚本放 `META-INF/scripts/file_rate_limit.lua`。

**令牌桶 Lua 语义**（单脚本原子：取令牌 + 补令牌 + 返回剩余）：

```lua
-- KEYS[1] = 桶 key
-- ARGV[1] = capacity（桶容量=突发上限）
-- ARGV[2] = refillRate（每秒补令牌数=平均速率）
-- ARGV[3] = now（epoch seconds，调用方传入，脚本内不用 TIME 命令以保证 Cluster 兼容）
-- ARGV[4] = requested（本次请求令牌数，通常 1）
-- ARGV[5] = ttlSeconds（桶 key 的 TTL，避免冷用户永驻）
-- 返回 {allowed(0/1), remaining, retryAfterSeconds}
local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
local now = tonumber(ARGV[3])
if tokens == nil then tokens = tonumber(ARGV[1]) ts = now end
-- 按 elapsed 补令牌，截断到 capacity
local elapsed = math.max(0, now - ts)
tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]))
local allowed = 0
local retry = 0
local requested = tonumber(ARGV[4])
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  -- 估算补到 requested 需要多久，作为 Retry-After 上界
  retry = math.ceil((requested - tokens) / tonumber(ARGV[2]))
end
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
return {allowed, math.floor(tokens), retry}
```

**设计要点**：
- **`now` 由调用方传入**：`System.currentTimeMillis()/1000`（应用层取秒）。脚本内不用 `TIME`/`TIME` 命令——Redis Cluster 下 `TIME` 走各自节点时钟不可靠，且 `EVALSHA` 要求脚本纯函数。与 `OrderIdempotencyService` 的 Lua 同源约定。
- **桶用 Hash 存**（`tokens` + `ts`），而非 string——避免「先 GET 再 SET」的 TOCTOU；HMSET + EXPIRE 在单脚本内原子。
- **桶 key 带 TTL**（`ttl-seconds`，默认 = 窗口时长的 2 倍，如 7200s）：冷用户桶自动回收，避免 Redis key 无界增长。桶重建时 `tokens=nil` → 初始化为满桶。
- **双桶「与」门**（上传场景 user + IP）：`FileRateLimitService.checkUpload(userId, ipHash)` 串行执行两桶 Lua（两桶各一次 `EVALSHA`）。任一 `allowed=0` 即整体 429，`Retry-After` 取两桶 retry 的 `max`。**不合并成单桶**——user 维度与 IP 维度语义不同（防单账号刷 vs 防多账号同源），合并会丢失维度信息。两次 `EVALSHA` 非原子但可接受：极端并发下最多多放过一个请求，令牌桶本身是近似限流，不要求强一致。
- **`requested` 通常 1**：上传按「文件数」计令牌（一个 multipart 部件 = 1 令牌），不按字节——字节级限流由 `max-file-size-bytes` + `max-request-size` 兜底（Spring multipart 配置，已是现状）。

**Key 布局**：
- `file:rl:user:upload:{userId}` —— per-user 上传令牌桶（Hash: tokens/ts）。
- `file:rl:ip:upload:{ipHash}` —— per-IP 上传令牌桶（IP 用 SHA-256 哈希存储，不落明文 IP，GDPR 友好）。
- `file:rl:ip:download:{ipHash}` —— per-IP 下载令牌桶。
- `file:rl:user:link:{userId}` —— per-user 链接刷新令牌桶。
- `file:rl:user:delete:{userId}` —— per-user 删除令牌桶。
- `file:dc:{fileId}` —— per-file 下载计数（INCR + EXPIRE，滑动窗口，反遍历告警，见 §4.3）。
- `file:slowban:{userId}` —— 软封禁标记（SET + TTL，见 §4.2）。

**默认配额**（`shopmall.file.rate-limit.*`，均可环境变量覆盖）：

| 维度 | 桶容量（突发） | 补令牌/秒（平均） | 说明 |
|---|---|---|---|
| user:upload | 10 | 0.2（≈12/分钟） | 单用户上传突发 10，平均每 5s 1 个 |
| ip:upload | 30 | 1（≈60/分钟） | 单 IP 上传突发 30，平均每秒 1 个 |
| ip:download | 60 | 5（≈300/分钟） | 单 IP 下载突发 60 |
| user:link | 20 | 0.5（≈30/分钟） | 链接刷新 |
| user:delete | 10 | 0.1（≈6/分钟） | 删除 |

> 桶容量与补速率均为**可配置**，生产按压测调参。令牌桶 `allowed=0` 时返回 429 + `Retry-After`（取 Lua 返回的 `retryAfterSeconds`，对齐 `ResponseBuilder.retryAfter`）。

**软封禁配额降级落地**：`FileRateLimitService` 取令牌前先 `EXISTS file:slowban:{userId}`；命中则把 `capacity` 与 `refillRate` 各 ×0.1 再传 Lua（应用层乘，非脚本内），实现「降级桶」。慢刷用户仍可操作但速率骤降，不阻断。`EXISTS` 是 O(1) 且绝大多数用户未封禁（key 不存在 → 即时返回 0），不在热点路径增加显著开销。

### 4.2 低俗慢刷检测 — `FileSlowBurnDetector`（新增，`@Component`）

> 慢刷特征：**速率在令牌桶阈值内**（不触发 429），但**长时间持续拉满**（如 1 小时内每 5s 上传 1 个 = 720 个）。令牌桶只看瞬时速率，拦不住长时段累积，需滑动窗口累计检测。

**真滑动窗口（多桶近似）**：用 N 个固定桶近似连续滑动窗口，避免单桶的「跨边界清零」缺陷（单小时桶在整点重置会让慢刷者卡在每小时 59 分刷完 99 个逃过阈值）。
- 窗口 `W`（默认 3600s）分 `B` 桶（默认 12 桶 × 5 分钟）。
- 上传/下载命中时，**同步** `INCR file:slow:user:{userId}:{bucketIdx}`（`bucketIdx = now / 300`）+ `EXPIRE {W + 桶宽}`（O(1)，不阻塞热点）。
- 阈值判定（累计窗口内总量 > `slow-burn-user-threshold`）需要读 N 个桶求和——**这步异步**：`@Async` 定时（`@Scheduled(fixedDelay = 60_000)`）批量扫近活跃用户桶求和，命中则 `SET file:slowban:{userId} 1 EX {slow-ban-ttl-seconds}` 并 `log.warn` + 告警。
- **同步只 INCR，异步判定**：热点路径（上传/下载）只做一次 `INCR EXPIRE`，不做求和；判定放后台，不拖慢请求线程。代价是软封禁有最多 1 分钟延迟——可接受（慢刷本就是低烈度攻击，秒级拦截非目标）。

**软封禁语义**：
- `file:slowban:{userId}` 命中 → `FileRateLimitService` 配额降级（§4.1，capacity×0.1 + refill×0.1）。
- **不直接拒绝**：避免误伤正常大客户批量上传（一次活动上传 200 张图）。降级而非阻断，配额骤降后大客户仍能慢慢传完，攻击者成本飙升。
- 软封禁 TTL（默认 1h）自然过期，无需手动解封；若需人工解封，`DEL file:slowban:{userId}` + `DEL file:slow:user:{userId}:*`。

**窗口桶清理**：每桶 `EXPIRE` 自动回收，无需额外任务。`@Async` 判定任务用 `SCAN file:slow:user:*` 增量扫近活跃用户（非 `KEYS`，避免阻塞 Redis）。

> **慢刷 vs 令牌桶分工**：令牌桶拦「瞬时突发」（高频爆破），慢刷检测拦「长时段累积」（低俗慢刷）。两者正交，不替代。

### 4.3 反遍历防护

1. **文件 ID 不可枚举**：`StoredFile.id` 用 `UUID.randomUUID()`（CSPRNG），已是现状，保留。日期分桶路径 `yyyy/MM/dd` 可枚举但**文件名是 UUID**，无法猜。
2. **签名 nonce + 单次性**：扩展 `FileLinkSigner` 载荷纳入 `nonce`（每次签发 `UUID.randomUUID()`），签名绑定 `fileId+scope+expires+nonce`。
   - **nonce 不强制服务端去重**（合法用户可能用同一链接重复下载，强制去重会破坏正常体验）。
   - **nonce 的真实作用**：让「同一 (fileId, scope, expires)」每次签发的签名不同——攻击者即便拿到一个签名，也无法用它推导出另一组参数的有效签名；且为「单次性下载链接」留扩展点（后续可选 `SETNX file:nonce:{nonce} 1 EX ttl` 强制一次性，命中已用 nonce → 410 Gone，针对高敏文件按 scope 开关）。
3. **短 TTL**：下载链接默认 TTL 300s（`download-token-ttl-seconds`）。**敏感度分级 TTL**（§5.4）：`public` 走短 TTL（60s），`user:` 中 TTL（300s），高敏文件可签更短。短 TTL 缩小签名泄漏后的可用窗口。
4. **per-file 下载计数告警**：`INCR file:dc:{fileId}` + `EXPIRE 3600`，单文件 1 小时下载 > 阈值（如 100）→ 告警。
   - **计数失真修正**：S3 走预签名 URL 302 重定向后，后续下载流量不回应用，`INCR` 只计「应用收到的下载请求数」而非「实际字节下载数」。因此 `file:dc` 的语义是「应用层下载鉴权次数」，对 S3 文件它反映的是「签名链接被使用次数」——仍是泄漏的信号（签名链接被高频访问）。本地存储则计真实下载次数。**两种后端下，计数都反映「签名链接活跃度」，告警阈值一致**。
   - 不阻断（合法分享场景），仅告警 + 可人工吊销（`file:revoked:{fileId}`，后续项）。
5. **下载端点不暴露存在性**：验签失败 / 文件不存在 / 非 scope 命中 / 内容已被删，**统一返回 404**（不返回 403），关闭存在性预言机（对齐订单详情非 owner 返回 404 的模式）。响应体不区分原因，仅记审计日志。
6. **`public` scope 反遍历加固**：`public` 链接任何人可访问，是遍历最大风险面。除短 TTL 外，`public` scope 的下载必须命中 `file:dc` 计数（即便重定向也先 `INCR` 再 302），且 `public` 链接签发时强制短 TTL（§5.4），不支持长 TTL public 链接。

### 4.4 上传内容校验 — `FileContentValidator`（新增，`@Component`）

> 反刷不仅要限频次，还要挡恶意内容。跨境图片站若允许任意类型上传，攻击者可上传 `.html`/`.svg`（含脚本）/`.exe`，配合 `public` 链接做钓鱼或存储型 XSS。**信任客户端 `Content-Type` 是常见漏洞**——必须服务端二次校验。

**双层校验**（在 `FileServiceImpl.storeUpload` 落地，先于写盘/传 S3）：
1. **扩展名白名单 + ContentType 白名单**：两者必须一致通过。白名单按业务定（泳装站：`jpg/jpeg/png/webp/avif/gif` 图片 + `mp4/webm` 视频，视频可选）。配置 `shopmall.file.allowed-content-types`（列表）与 `allowed-extensions`。客户端声明的 `Content-Type` 不在白名单 → `ParamErrorException`（400）。
2. **魔数嗅探（magic bytes）**：读流头部 N 字节（如 16 字节）比对已知文件签名（JPEG `FFD8FF`、PNG `89504E47`、WebP `52494646...57454250`、GIF `47494638` 等）。**魔数与扩展名/ContentType 不一致** → 拒绝（防 `evil.php` 改名 `evil.jpg`）。用 Apache Tika 或自维护签名表（lean 倾向自维护，避免重依赖）。
3. **流式校验**：魔数嗅探只读头部，不缓冲整文件；嗅探后剩余字节继续流式写盘 + SHA-256（复用现有 `writeAndHash`，在其前插一个「先读头部校验再续写」的包装流）。
4. **下载侧 Content-Disposition**：现有 `FileController.download` 已用 `ContentDisposition.attachment()` + `X-Content-Type-Options: nosniff`（防浏览器嗅探内联执行），保留。**S3 预签名 URL 必须带 `response-content-disposition=attachment` 与 `response-content-type`**（预签名时设 `ResponseContentDisposition` / `ResponseContentType`），否则浏览器直接内联渲染 SVG/HTML 触发 XSS。

> **不在本期做病毒扫描**（ClamAV，见 §十一后续），但内容类型 + 魔数双层校验已挡住绝大多数恶意上传向量。

### 4.5 Redis 故障降级策略

> 限流、慢刷计数、元数据缓存都依赖 Redis。Redis 故障时必须有明确降级，否则限流服务挂 = 上传全挂 或 全开。

**逐功能 fail 策略**：

| 功能 | Redis 故障时 | 理由 |
|---|---|---|
| 令牌桶限流（上传/下载/链接/删除） | **fail-open（放行）** + 记审计告警 | 限流是「防滥用」非「防破坏」；Redis 挂时拒绝上传会让全站文件功能不可用，可用性损失 > 滥用风险。靠 Spring multipart 大小限制 + 内容校验 + DB 落库兜底基础安全 |
| 慢刷计数 INCR | **fail-skip**（跳过计数，记审计） | 慢刷检测本就异步、容忍延迟，跳过一次计数不影响整体判定 |
| 软封禁检查 `EXISTS file:slowban` | **fail-open**（视为未封禁） | 同限流，故障时不连坐降级正常用户 |
| 元数据缓存 `file:meta` | **fail-through**（穿透到 DB） | 缓存只是加速层，miss 直查 DB，正确性靠 DB 保证；Redis 挂时 DB 承压但功能不中断 |
| per-file 下载计数 `file:dc` | **fail-skip**（跳过计数） | 仅告警用，跳过不阻断 |

**实现**：`FileRateLimitService` / `FileMetadataCacheService` 捕获 `RedisConnectionFailureException`，按上表降级并 `log.warn`。**不放任异常冒泡到 `GlobalExceptionHandler` 的 500**——限流故障必须被服务自身吸收为降级行为，不让用户看到 500。

> **fail-open 的代价**：Redis 挂窗口内限流失效，攻击者可趁虚刷量。但上传仍受 `max-file-size-bytes` / `max-batch-size` / 内容校验 / DB 写入瓶颈约束，单次最大伤害有界。Redis 恢复后令牌桶重建为满桶（无历史令牌），不补偿扣减——可接受。

---

## 五、可选签名（仅限特定用户访问）

### 5.1 扩展 `FileLinkSigner` — `service/FileLinkSigner.kt`（修改）

现有签名载荷 `"$fileId:$userId:$expires"`，扩展为 `"$fileId:$scope:$expires:$nonce"`：
- `scope: String` —— 访问授权声明，取值：
  - `user:{userId}` —— 仅该用户（等价现状语义，但走新载荷格式）。
  - `role:admin` —— 仅 ADMIN 角色。
  - `order:{orderNo}` —— 仅购买该订单的用户（后续订单-文件关联时用）。
  - `public` —— 任何人可访问（不签名鉴权，但仍受速率限制与下载计数）。
- `nonce: String` —— 每次签发的 CSPRNG UUID，纳入签名防重放探测。

> **迁移策略（一刀切，详见 §8.1 决策）**：签名载荷格式整体替换，无兼容期。
> - `sign(fileId, userId, ttl)` 旧重载**保留**仅作为内部向后兼容糖（内部委托 `sign(fileId, "user:$userId", ttl)` 并生成 nonce），**不对外暴露旧签名格式**。
> - `isValid(fileId, userId, expires, signature)` **旧重载删除**（无调用方依赖，一刀切后唯一验签入口是 `isValid(fileId, scope, expires, nonce, signature)`）；旧格式签名一律判失败 → 404。

### 5.2 下载鉴权 — `FileServiceImpl.openSignedDownload`（修改）

```kotlin
fun openSignedDownload(fileId, scope, expires, nonce, signature): DownloadableFile {
    if (!linkSigner.isValid(fileId, scope, expires, nonce, signature)) throw ResourceNotFoundException(...)
    // scope 命中判定：
    //  - "public" → 放行
    //  - "user:{id}" → 需调用方传入当前 userId（下载端点 permitAll，无 JWT），
    //      故 user scope 仍靠签名本身绑定（签名即凭证，userId 已在签名载荷）
    //  - "role:admin" → 下载端点需带 JWT 且 role=ADMIN（与 permitAll 冲突，见下）
    //  - "order:{orderNo}" → 需校验调用方拥有该订单（后续订单联动）
    ...
}
```

> **`role:` / `order:` scope 与下载端点 `permitAll` 的张力**：现有下载端点 `permitAll` 是为了让匿名签名链接可访问（前端 `<img>`/`<a>` 直连）。`user:` / `public` scope 无需 JWT。`role:admin` / `order:` scope 需要服务端二次鉴权（JWT 或订单所有权），此时**该类 scope 的下载端点走另一条需要 JWT 的路径**（如 `GET /api/files/{fileId}/secure-download`，`authenticated`），签名仅作为「已获签发」的凭证，真正鉴权靠 JWT + scope 声明一致性。本期实现 `user:` / `public` 两条 scope（permitAll 路径），`role:` / `order:` 留扩展点与 secure-download 端点骨架。

### 5.3 签发策略

- **上传响应**：默认签 `user:{ownerId}` scope 链接（语义等价现状，但走新 `scope`/`nonce` 载荷，URL 参数格式随之变更）。
- **`createDownloadLinks`**：增加可选 `scope` 参数（默认 `user:{ownerId}`），允许 owner 为自己的文件签 `public` 链接（分享场景）。
- **管理端**（后续）：ADMIN 可为任意文件签 `role:admin` 或 `public` 链接。

### 5.4 敏感度分级 TTL

`download-token-ttl-seconds`（现状单值）拆为按 scope 分级的 TTL 表，越开放越短：

| scope | 应用签名 TTL | 理由 |
|---|---|---|
| `public` | 60s（`public-ttl-seconds`） | 最大风险面，短 TTL 缩小泄漏窗口；前端按需刷新链接 |
| `user:{id}` | 300s（`user-ttl-seconds`，现状默认） | 仅本人用，中等窗口 |
| `role:admin` | 180s | 走 secure-download，JWT 兜底，可中 TTL |
| `order:{no}` | 300s | 订单用户下载凭证，中 TTL |

- **配置**：`shopmall.file.signing.public-ttl-seconds` / `user-ttl-seconds` / `admin-ttl-seconds` / `order-ttl-seconds`，向后兼容保留 `download-token-ttl-seconds` 作为 `user-ttl` 别名（若未配 `user-ttl-seconds` 则回退）。
- **`FileLinkSigner.sign(fileId, scope, ttl)` 的 `ttl` 由签发方按 scope 查表传入**，签名器本身不感知 scope→ttl 映射（保持签名器无状态、纯密码学职责）。映射在 `FileServiceImpl` / 控制器层完成。
- **S3 presign TTL 联动**：S3 预签名 TTL 取 `min(应用签名 TTL, s3.presign-ttl-seconds)`，保证 S3 URL 不比应用签名长寿（§3.1 约束）。

> 这把原先散落在 §4.3、§3.1、§3.5 的 TTL 约束集中到一处可配置来源，消除「敏感文件可签更短 TTL」的悬空描述。

---

## 六、元数据高并发查询优化（用户明确要求）

> **核心诉求**：文件元数据落 DB（现状 `file_metadata` 表已满足），**优化高并发查询**。现状 `findAllByOwnerIdOrderByCreatedAtDesc` 全量加载、无分页无缓存，是热点瓶颈。

### 6.1 分页 — `StoredFileRepository`（修改）

```kotlin
interface StoredFileRepository : JpaRepository<StoredFile, UUID> {
    // 废弃全量加载，改分页
    fun findByOwnerIdOrderByCreatedAtDesc(ownerId: Long, pageable: Pageable): Page<StoredFile>
    fun findAllByIdInAndOwnerId(ids: Collection<UUID>, ownerId: Long): List<StoredFile>
    fun findByIdAndOwnerId(id: UUID, ownerId: Long): StoredFile?

    // 验签下载短路径：按 id 查（不强制 ownerId，鉴权靠签名）
    fun findById(id: UUID): StoredFile?
}
```

`FileController.listMine` 改 `@RequestParam page, size`（默认 0/20，上限 100），返回 `Page`。**消除全量加载**。

### 6.2 复合索引 — `StoredFile` 实体（修改）

现有索引 `idx_file_metadata_owner_created(owner_id, created_at)` 已覆盖按 owner 分页排序。新增：
- `idx_file_metadata_owner_storage(owner_id, storage)` —— 按后端过滤（管理端对账用）。
- `idx_file_metadata_sha256(sha256)` —— 去重查询（同内容秒传/排查）。
- `idx_file_metadata_created(created_at)` —— 全局对账扫描（按时间分批）。

> `ddl-auto=update` 自动建索引。生产建议 Flyway 显式管理（与订单模块一致，本期不阻塞）。

### 6.3 Redis 元数据缓存 — `FileMetadataCacheService`（新增，`shared/`）

> 下载验签场景高频 `findById`，缓存兜住热点读。

- **缓存 key**：`file:meta:{fileId}`，value 为 `StoredFile` 的轻量投影 `FileMetaDto`（id/ownerId/storage/storedKey/relativePath/contentType/sizeBytes/originalFilename）。
- **序列化**：`StringRedisTemplate` + JSON（对齐项目既有 Redis string 用法，不引入 `RedisTemplate<Object>`）。`FileMetaDto` 用 Jackson 序列化（`tools.jackson.databind.ObjectMapper`，遵循记忆 [[jackson3-tools-package]]）。
- **TTL**：`shopmall.file.cache-ttl-seconds`（默认 300s）。
- **写策略：write-through**（非单纯 cache-aside）：`upload` 落库后立即 `SET` 缓存（写穿），省去首次下载的回填 miss。`delete` 删 DB 后立即 `DEL` 缓存。
- **缓存击穿防护（singleflight）**：热点 fileId 在 TTL 到期瞬间会涌来大量 miss 并发查 DB。用 Redis `SETNX file:meta:lock:{fileId} 1 EX 3` 做单飞令牌——抢到锁的请求查 DB + 回填，未抢到的**短自旋等待**（如 50ms 重试读缓存，上限 3 次）而非并发打 DB。避免缓存击穿放大 DB 压力。
- **缓存穿透防护**：查 DB 不存在的 fileId，写**空值标记** `SET file:meta:{fileId} "" EX 30`（短 TTL 30s，区别于正常值 300s），防止攻击者用随机 fileId 反复打 DB。命中空值标记直接 404，不查 DB。
- **不缓存敏感签名**：签名每次签发新 nonce，不缓存。
- **owner 列表不缓存**：列表分页结果缓存价值低（频繁变动 + 分页组合多），仅缓存单文件元数据（下载验签热点）。

> **`delete` 时序与 staging 一致性**：现有删除先「移动内容到 `.deleting/` 暂存」再「删 DB」再「删暂存内容」。缓存 `DEL` 必须在「删 DB 成功后、删暂存内容前」执行——此时 DB 已无元数据，后续下载验签若缓存还在会指向已删 DB 行（取不到 storedKey 走 DB miss → 空值标记 → 404，正确）。若 `DEL` 失败，靠 TTL 300s 兜底过期，最坏缓存陈旧 300s 内下载返回「DB miss 404」（因为缓存 hit 但走 DB 兜底校验内容存在性——`openSignedDownload` 拿到 storedKey 后 backend.exists 校验，本地内容已移走 → 404）。**S3 模式**：删 DB 后 S3 对象尚在（延迟删除/对账清理），缓存 `DEL` 后下载验签 DB miss → 404，正确；即便缓存陈旧指向 S3 对象，对象在 presign TTL 外不可访问，安全。

### 6.4 下载鉴权短路径优化

现状 `openSignedDownload` 先 `linkSigner.isValid`（纯计算，无 IO），再 `findByIdAndOwnerId`（落库）。优化后热点路径 IO 从 DB 降到 Redis：

1. `linkSigner.isValid(fileId, scope, expires, nonce, signature)` 验签（纯 HMAC 计算，无 IO）。
2. `FileMetadataCacheService.get(fileId)`（Redis，命中率高）→ 取 `FileMetaDto`（storedKey/storage/ownerId/contentType）。
3. **scope 与元数据交叉校验**（关键，防签名-元数据错配）：
   - `user:{uid}` → `uid` 必须等于 `dto.ownerId`（签名是 owner 签的，元数据也必须是该 owner 的；防攻击者用自己的签名访问他人 fileId——但 fileId 已在签名载荷且不可篡改，此处是双保险）。
   - `public` → 放行（无需匹配 ownerId）。
   - `role:` / `order:` → 走 secure-download 端点，JWT/订单校验在此补全。
4. **backend 路由**：按 `dto.storage` 选 `LocalStorageBackend` 或 `S3StorageBackend`（`@Autowired Map<String, StorageBackend>` by id，或 `StorageBackendRouter`）。
5. `backend.open(dto.storedKey)` → 本地 `BlobContent.Stream` 流式 / S3 `BlobContent.Redirect` 302。

**热点路径开销**：1 次 HMAC + 1 次 Redis GET +（本地）1 次文件读 /（S3）0 次字节传输。DB 仅在缓存 miss（含 singleflight 命中后回填）时命中。

> **scope 校验放在缓存 hit 之后**而非验签时：因为验签是纯密码学（不读元数据），而 `user:{uid}` 是否等于 owner 需要 `dto.ownerId`——必须先取元数据。顺序固定：验签 → 取元数据 → scope 交叉校验 → 开内容。任何一步失败统一 404（§4.3 第 5 条）。

**热点下载路径 IO 从 DB 降到 Redis**（验签计算 + 一次 Redis GET），DB 仅在缓存 miss 时命中。

### 6.5 连接池与批量

- `spring.jpa.hibernate.open-in-view: false`（已是现状）—— 列表/下载不在请求线程持有 EntityManager。
- 批量上传 `saveAllAndFlush`（已是现状）—— 批量 INSERT，避免逐条。
- S3 批量删除用 `DeleteObjectsRequest`（批量）而非逐个。

---

## 七、对账与清理（S3 孤儿 + 慢刷封禁）

### 7.1 S3 孤儿对象对账 — `FileStorageReconciler`（新增，`@Scheduled`）

> `FileServiceImpl.upload` 的 try-catch 已清理「存储成功但 DB 失败」的孤儿。但 S3 `delete` 失败、进程崩溃等场景仍会产生孤儿。

- `@Scheduled(fixedDelay = 6h)`：扫 `file_metadata` 表分批（按 `createdAt` 索引），对 `storage=s3` 的行 `S3Client.headObject` 校验存在性；DB 有但 S3 无 → 标记 `missing_content`（审计）。
- 反向：S3 list objects（按前缀分桶）→ DB 无记录的 → 孤儿对象，`delete`（或移到冷备 bucket）。
- **多实例限流**：`SET lock:file-reconcile NX EX 21600` 选主（对齐订单超时扫描选主模式）。
- **依赖 `@EnableScheduling`**：订单模块已加（`ShopMallApplication.kt`），复用。

### 7.2 慢刷封禁过期清理

`file:slowban:{userId}` TTL 自然过期。`FileSlowBurnDetector` 的滑动窗口桶 `EXPIRE` 自动清理。无需额外任务。

---

## 八、API 与安全

### 8.1 端点（`controller/FileController.kt`，基于现状修改）

| 方法 | 路径 | 鉴权 | 说明 | 反刷 |
|---|---|---|---|---|
| POST | `/api/files` | authenticated | 批量上传（multipart `files`） | user+IP 令牌桶 + 慢刷检测 |
| GET | `/api/files/mine?page=&size=` | authenticated | 我的文件**分页**列表 | user:link 令牌桶 |
| POST | `/api/files/batch/links` | authenticated | 批量刷新下载链接（可选 `scope`） | user:link 令牌桶 |
| GET | `/api/files/{fileId}/download?scope=&expires=&nonce=&signature=` | permitAll | 签名下载（`user:` / `public` scope） | IP 令牌桶 + per-file 下载计数 |
| GET | `/api/files/{fileId}/secure-download?...` | authenticated | 需 JWT 的 scope（`role:admin` / `order:`）下载 | IP 令牌桶 |
| DELETE | `/api/files/{fileId}` | authenticated | 删除单文件 | user:delete 令牌桶 |
| DELETE | `/api/files/batch` | authenticated | 批量删除 | user:delete 令牌桶 |

> **【决策】下载端点签名参数一刀切迁移 `userId` → `scope`**：文件模块未上线生产，无前端存量调用与历史签名链接的兼容包袱，**不做兼容期、不保留 `userId` 旧参数**。
> - 旧签名载荷 `"$fileId:$userId:$expires"` → 新载荷 `"$fileId:$scope:$expires:$nonce"`，`isValid` 不再接受无 `scope`/`nonce` 的旧签名（直接判失败 → 404）。
> - 下载端点 `GET /api/files/{fileId}/download` 的查询参数：~~`userId`~~ `expires` `signature` → `scope` `expires` `nonce` `signature`。
> - `user:{userId}` scope 仍把 userId 编码进 `scope` 字段（等价语义），但走新载荷格式。
> - `FileLinkSigner.sign(fileId, userId, ttl)` 旧重载**保留**仅作为内部向后兼容糖（内部委托 `sign(fileId, "user:$userId", ttl)` 并生成 nonce），**不对外暴露旧签名格式**；外部一律用带 `scope` 的新签发路径。
> - 实现侧：`FileController.download`、`FileService.openSignedDownload`、`FileMetadataResponse` 同步改为 `scope`/`nonce` 参数；`FileLinkSigner` 的旧 `isValid(fileId, userId, expires, signature)` 重载删除（无调用方依赖，安全删除）。

### 8.2 SecurityConfig 调整（已预留，微调）

现状已 `GET /api/files/*/download` `permitAll`、`/api/files/**` `authenticated`。新增 `secure-download` 走 `authenticated`（已被 `/api/files/**` 覆盖，无需额外规则）。CORS `allowedHeaders` 无需新增（无自定义头）。

### 8.3 配置追加 — `application.yaml`（基于现状 `shopmall.file.*` 扩展）

```yaml
shopmall:
  file:
    storage-path: "${FILE_STORAGE_PATH:./storage}"
    base-url: "${FILE_BASE_URL:http://localhost:8080}"
    signing-secret: "${FILE_SIGNING_SECRET:...}"          # 现状
    download-token-ttl-seconds: "${FILE_DOWNLOAD_LINK_TTL_SECONDS:300}"  # 现状
    max-batch-size: "${FILE_MAX_BATCH_SIZE:20}"            # 现状
    max-file-size-bytes: "${FILE_MAX_FILE_SIZE_BYTES:104857600}"  # 现状
    default-backend: "${FILE_DEFAULT_BACKEND:local}"       # 新增：local / s3
    cache-ttl-seconds: "${FILE_META_CACHE_TTL:300}"        # 新增：元数据缓存 TTL
    s3:                                                     # 新增
      region: "${FILE_S3_REGION:}"
      endpoint: "${FILE_S3_ENDPOINT:}"                     # 兼容 MinIO/R2
      bucket: "${FILE_S3_BUCKET:}"
      access-key: "${FILE_S3_ACCESS_KEY:}"
      secret-key: "${FILE_S3_SECRET_KEY:}"
      presign-ttl-seconds: "${FILE_S3_PRESIGN_TTL:300}"
    rate-limit:                                             # 新增
      user-upload-capacity: "${FILE_RL_USER_UPLOAD_CAP:10}"
      user-upload-refill: "${FILE_RL_USER_UPLOAD_REFILL:0.2}"
      ip-upload-capacity: "${FILE_RL_IP_UPLOAD_CAP:30}"
      ip-upload-refill: "${FILE_RL_IP_UPLOAD_REFILL:1}"
      ip-download-capacity: "${FILE_RL_IP_DOWNLOAD_CAP:60}"
      ip-download-refill: "${FILE_RL_IP_DOWNLOAD_REFILL:5}"
      user-link-capacity: "${FILE_RL_USER_LINK_CAP:20}"
      user-link-refill: "${FILE_RL_USER_LINK_REFILL:0.5}"
      user-delete-capacity: "${FILE_RL_USER_DELETE_CAP:10}"
      user-delete-refill: "${FILE_RL_USER_DELETE_REFILL:0.1}"
      slow-burn-window-seconds: "${FILE_SLOW_BURN_WINDOW:3600}"
      slow-burn-user-threshold: "${FILE_SLOW_BURN_USER_THRESHOLD:100}"
      slow-ban-ttl-seconds: "${FILE_SLOW_BAN_TTL:3600}"
      download-count-window-seconds: "${FILE_DC_WINDOW:3600}"
      download-count-threshold: "${FILE_DC_THRESHOLD:100}"
      bucket-ttl-seconds: "${FILE_RL_BUCKET_TTL:7200}"     # 令牌桶 key TTL，冷用户回收
      slow-burn-bucket-seconds: "${FILE_SLOW_BURN_BUCKET:300}"  # 滑动窗口分桶宽（5 分钟）
      slow-burn-buckets: "${FILE_SLOW_BURN_BUCKETS:12}"   # 窗口分桶数（12 × 5min = 1h 窗口）
    signing:                                                   # 新增：分级 TTL（§5.4）
      public-ttl-seconds: "${FILE_SIGN_PUBLIC_TTL:60}"
      user-ttl-seconds: "${FILE_SIGN_USER_TTL:300}"        # 未配则回退 download-token-ttl-seconds
      admin-ttl-seconds: "${FILE_SIGN_ADMIN_TTL:180}"
      order-ttl-seconds: "${FILE_SIGN_ORDER_TTL:300}"
    content:                                                   # 新增：上传内容校验（§4.4）
      allowed-content-types: "${FILE_ALLOWED_CT:image/jpeg,image/png,image/webp,image/avif,image/gif}"
      allowed-extensions: "${FILE_ALLOWED_EXT:jpg,jpeg,png,webp,avif,gif}"
      magic-bytes-check-enabled: "${FILE_MAGIC_CHECK:true}"
      magic-sniff-bytes: "${FILE_MAGIC_SNIFF_BYTES:16}"
```

### 8.4 异常 — `handler/BusinessException.kt`（追加子类）

| 异常 | HttpStatus | 默认消息 |
|---|---|---|
| `FileRateLimitException` | 429 TOO_MANY_REQUESTS | 操作过于频繁，请稍后再试 |
| `FileStorageException` | 503 SERVICE_UNAVAILABLE | 文件存储暂时不可用 |

> `FileRateLimitException` 由 `GlobalExceptionHandler` 映射时带 `Retry-After` 头（复用 `ResponseBuilder.retryAfter`）。`MaxUploadSizeExceededException` 已有 handler（现状）。

### 8.5 响应模型 — `FileMetadataResponse`（修改）

现状 `signedDownloadUrl` + `downloadExpiresAt: Instant`（**违反项目时间约定**，应为 `LocalDateTime`）。改为：
- `downloadExpiresAt: LocalDateTime`（遵循项目时间约定，对齐全库）。
- 新增 `scope: String`（当前链接的 scope）。
- 新增 `storage: String`（后端标识，前端可据此区分本地/S3 展示）。

---

## 九、待新增 / 修改文件清单

**新增**（均在既有包结构内）：
- `shared/StorageBackend.kt`（接口 + `BlobContent` / `StoredBlob`）
- `shared/LocalStorageBackend.kt`、`shared/S3StorageBackend.kt`、`shared/S3Config.kt`（`@ConditionalOnProperty` 仅 s3 模式装配）
- `shared/StorageBackendRouter.kt`（按 `StoredFile.storage` 选 backend，`Map<String, StorageBackend>` by id）
- `shared/FileRateLimitService.kt` + `META-INF/scripts/file_rate_limit.lua`
- `shared/FileSlowBurnDetector.kt`（同步 INCR + `@Async` 判定）
- `shared/FileMetadataCacheService.kt`（write-through + singleflight + 空值标记）
- `shared/FileContentValidator.kt`（白名单 + 魔数嗅探，§4.4）
- `service/impl/FileStorageReconciler.kt`（`@Scheduled` + 选主）
- `config/S3Properties.kt`（或并入 `FileProperties` 扩展）

**修改**：
- `service/FileLinkSigner.kt` — 载荷整体替换为 `$fileId:$scope:$expires:$nonce`；`sign(fileId,userId,ttl)` 旧重载保留作内部糖（委托新签发），`isValid` 旧重载**删除**（一刀切，旧格式签名判失败 → 404，见 §8.1 决策）。
- `service/FileService.kt` — 接口加 `scope` 参数、`openSignedDownload` 签名参数变更。
- `service/impl/FileServiceImpl.kt` — 委托 `StorageBackendRouter`、注入 `FileRateLimitService` / `FileMetadataCacheService` / `FileContentValidator` / `FileSlowBurnDetector`、列表分页、`Instant`→`LocalDateTime`、分级 TTL 查表。
- `controller/FileController.kt` — 列表分页参数、下载端点签名参数 `scope/nonce`、`secure-download` 端点、S3 `BlobContent.Redirect` 302 分支。
- `entity/jdbc/StoredFile.kt` — 加 `storage` 字段 + 新索引。
- `repository/StoredFileRepository.kt` — 分页查询、`findById`。
- `config/FileProperties.kt` — 扩展 `defaultBackend` / `cacheTtl` / `s3` / `rateLimit` / `signing`（分级 TTL）/ `content`（白名单）。
- `config/SecurityConfig.kt` — 确认 `secure-download` 走 `authenticated`（已被 `/api/files/**` 覆盖）。
- `handler/BusinessException.kt` — 追加 `FileRateLimitException` / `FileStorageException`。
- `handler/GlobalExceptionHandler.kt` — `FileStorageException`/`S3Exception` 503 + Retry-After（可复用现有 `DataAccessException` 模式或新增）。
- `src/main/resources/application.yaml` — 扩展 `shopmall.file.*`（含 `signing.*` / `content.*` / `rate-limit.bucket-ttl` 等）。
- `ShopMallApplication.kt` — 确认 `@EnableAsync`（慢刷 `@Async` 判定需要；`@EnableScheduling` 订单模块已加）。
- `build.gradle.kts` — 加 `software.amazon.awssdk:s3` 依赖。

**复用既有**（不重写）：
- `FileLinkSigner` 的 HMAC-SHA256 + 常量时间比较模式（扩展载荷）。
- `FileServiceImpl` 的删除 staging 恢复、`safeOriginalFilename`、`writeAndHash` 流式 SHA-256、`resolveStoredPath` 边界校验（上移到 backend）。
- `StringRedisTemplate` + `DefaultRedisScript` Lua 模式（对齐 `OrderIdempotencyService`）。
- `ResponseBuilder` / `BusinessException` 子类模式 / `@AuthenticationPrincipal userId: Long`。
- `@EnableScheduling`（订单模块已加）。
- `StoredFile` 的裸 `ownerId` 去归一化哲学。

---

## 十、验证方案

1. **编译与启动**：`./gradlew bootRun`，确认 `file_metadata` 表新增 `storage` 列与索引，S3 bean 在无 S3 配置时不报错（`default-backend=local` 时 S3 bean 懒加载/条件装配）。
2. **单元测试**（H2 + `application-test.yaml`）：
   - 上传：本地后端写文件 + 元数据落库 + Redis 缓存写入；S3 后端用 mock `S3Client` 断言 `putObject` 调用 + 预签名 URL 生成。
   - 限流：令牌桶耗尽 → `FileRateLimitException` 429 + `Retry-After`；补令牌后恢复。
   - 慢刷：模拟 1 小时 100 次上传 → 软封禁标记写入 + 配额降级（桶容量 ×0.1）。
   - 反遍历：per-file 下载计数超阈值告警；验签失败/文件不存在统一 404。
   - 签名 scope：`user:{id}` 命中、`public` 命中、篡改 `scope` 签名失效；`nonce` 纳入签名。
   - **一刀切迁移**（§8.1 决策）：旧格式签名（无 `scope`/`nonce` 的 `$fileId:$userId:$expires` 载荷）经 `isValid` 判失败 → 下载端点返回 404；下载端点不识别旧 `userId` 查询参数（缺 `scope`/`nonce`/`signature` → 400 或 404）；`FileLinkSigner` 不存在旧 `isValid(fileId,userId,expires,signature)` 重载（编译期断言，签名迁移已无回退路径）。
   - 元数据缓存：首次 `findById` 落 DB + 回填 Redis；第二次命中 Redis 不落 DB；`delete` 后缓存失效。
   - **缓存击穿**：TTL 到期瞬间 100 并发同 fileId `get` → 仅 1 个查 DB（singleflight 锁），其余自旋命中缓存后回填；DB 查询数 == 1。
   - **缓存穿透**：随机不存在 fileId 连续请求 → 首次落 DB + 写空值标记，后续命中空值标记不落 DB。
   - 分页：`findByOwnerIdOrderByCreatedAtDesc` + `Pageable`，断言分页 SQL 含 `LIMIT` + `OFFSET`，不全量加载。
   - **内容校验**：`.html`/`.svg`/`.exe` 上传 → 400（白名单拒）；`evil.php` 改名 `evil.jpg` 且魔数非 JPEG → 400（魔数嗅探拒）；正常 JPEG/PNG 通过。
   - **Redis 故障降级**：mock `RedisConnectionFailureException` → 上传 fail-open 放行 + 审计日志；下载缓存 fail-through 落 DB；不抛 500。
   - **分级 TTL**：`public` 链接 expires = now+60s；`user:` 链接 expires = now+300s；篡改 TTL 越界签名失效。
3. **Testcontainers-PostgreSQL 集成测试**：复合索引命中（`EXPLAIN` 验证 `idx_file_metadata_owner_created` 用于分页排序）；多实例 `@Scheduled` 对账选主不重复（`lock:file-reconcile` 只一实例持锁）。
4. **并发压测**：100 线程同用户上传 → 令牌桶串行化，成功数 ≤ 桶容量+补令牌；100 线程同文件下载 → 下载计数告警触发；元数据缓存命中下 DB QPS 近 0；**软封禁用户**配额降级后成功数 ≤ 降级桶容量。
5. **S3 集成**：用 LocalStack（`S3`）或 MinIO 跑真实 `putObject`/`presign`/`delete`；孤儿对账模拟「DB 有 S3 无」与「S3 有 DB 无」两种孤儿；**断言预签名 URL 含 `response-content-disposition=attachment`**（防内联 XSS）；S3 `SlowDown` 异常 → 503 + Retry-After。
6. **手工冒烟**：上传 → 返回签名链接 → 浏览器直连下载（permitAll）→ 删除 → 再下载 404；切换 `default-backend=s3` → 上传走 S3 → 下载 302 重定向到预签名 URL；`public` scope 链接 60s 后过期失效。

---

## 十一、范围边界与后续迭代

- **病毒扫描**：本期不做上传内容病毒扫描。后续接 ClamAV 或 S3 病毒扫描服务，上传后异步扫描，命中软删 + 告警。
- **图片处理**：本期不做缩略图/压缩。后续接图片处理服务（S3 触发 Lambda 或应用内 ImageIO），产物走同一存储抽象。
- **CDN**：S3 预签名 URL 可前置 CloudFront，CNAME 配置在 `shopmall.file.s3.cdn-base-url`，下载链接用 CDN 域名。
- **秒传（去重）**：`sha256` 索引已留（§6.2），后续可按 `sha256 + ownerId` 查已有文件，命中则复用（仅元数据新增，内容不重复存）。
- **配额管理**：本期无单用户存储配额。后续按 `ownerId` 统计总量，超配额拒绝上传（对齐反刷配额模式）。
- **`role:` / `order:` scope 完整实现**：本期实现 `user:` / `public`，留 `secure-download` 端点骨架。后续订单-文件关联后实现 `order:` scope（下载端点验签 + 订单所有权校验）。
- **签名吊销**：本期靠短 TTL 自然过期。后续引入 `file:revoked:{fileId}` Redis 黑名单，`openSignedDownload` 校验。
- **schema 迁移**：本期 `ddl-auto=update` 自动加列加索引。生产建议 Flyway（与订单模块一致，后续统一引入）。
