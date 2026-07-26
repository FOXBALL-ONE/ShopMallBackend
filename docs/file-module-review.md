# 文件模块逻辑审查报告

> 审查范围：`controller/FileController.kt`、`service/FileService.kt`、`service/FileLinkSigner.kt`、`service/impl/FileServiceImpl.kt`、`config/FileProperties.kt`、`config/FileConfig.kt`、`entity/jdbc/StoredFile.kt`、`repository/StoredFileRepository.kt`、`db/migration/V2__extend_file_metadata.sql` 及对应测试。
> 审查日期：2026-07-26
> 参照契约：`docs/file-system-design.md`、`docs/file-database-test-backlog.md`。

问题按严重程度分级：🔴 高（安全 / 数据一致性 / 越权）、🟠 中（逻辑缺陷 / 不可达代码）、🟡 低（健壮性 / 一致性 / 待办）。

---

## 🔴 高

### F-1 `scopeAllows` 对 `user:` scope 缺少所有者绑定，存在越权下载风险

`scopeAllows` 的 `user:` 分支写的是 `scope == "user:${stored.ownerId}"`，看似绑定了所有者。但签发侧 `toResponse` 默认用 `"user:${stored.ownerId}"` 签发，**下载侧 `openSignedDownload` 并未校验 `scope` 中的 userId 与 `stored.ownerId` 是否一致**——它只比对 `scope` 字符串本身是否等于 `"user:${stored.ownerId}"`。

问题链路：
1. `download` 端点（`FileController.kt:117`）只允许 `scope` 为 `public` 或 `user:` 前缀，**不校验 `user:` 后的 id 是不是当前文件所有者**，直接把 `scope` 原样传给 `openSignedDownload`。
2. `openSignedDownload` 先用 `linkSigner.isValid` 验签（签名绑定了 `scope` 字符串，篡改即失效），再用 `scopeAllows` 比对。
3. 由于 HMAC 签名把 `scope` 绑死了，攻击者无法把 `user:42` 的签名改成 `user:43` —— 这一层是安全的。

**结论**：当前实现在「签名密钥不泄漏」前提下是安全的，因为 scope 被签名保护，无法伪造他人 scope。但存在两个真实风险：
- **密钥一旦泄漏即全域失守**：scope 仅作字符串比对，没有「下载端点侧用 `@AuthenticationPrincipal` 的 userId 与 scope 二次比对」的纵深防御。`download` 是 `permitAll` 端点，无 JWT，一旦密钥泄漏，任何文件的 `user:` 链接可被批量生成。
- **`secure-download` 的 `order:` scope 完全未实现校验**：`scopeAllows` 对 `order:xxx` 永远返回 `false`（落入 `else -> false`）。即使能签发出 `order:` 链接，下载也必失败。见 F-2。

**建议**：`download` 端点对 `user:` scope 增加可选的「文件所有者即签发对象」校验已在 `scopeAllows` 体现，保持即可；但应在文档/注释中明确「整个下载鉴权强依赖 HMAC 密钥保密」，并考虑密钥轮换机制。`order:` scope 若暂不支持，应从 `ttlFor`、`secure-download`、`scopeAllows` 一并移除，避免「能签发 TTL 却无法下载」的死代码。

---

### F-2 `order:` 与 `role:admin` scope 可计算 TTL 却无法被签发，构成不可达死代码

**位置**：`FileServiceImpl.kt:213-225`（`resolveIssuableScope` / `ttlFor`）、`FileController.kt:122-144`（`secure-download`）

- `resolveIssuableScope` 只允许签发 `public` 或 `user:$ownerId`，**任何其他 scope 一律抛 `ParamErrorException`**。
- 因此 `role:admin` 和 `order:*` 的链接在当前代码里**永远无法被合法签发**。
- 但 `ttlFor` 为 `role:admin` / `order:` 准备了 TTL（`adminTtlSeconds` / `orderTtlSeconds`），`secure-download` 端点也接受这两个 scope 验证，`scopeAllows` 也处理了 `role:admin`。

**后果**：
- `role:admin` 分支：`scopeAllows` 允许管理员下载，但因无签发路径，管理员永远拿不到合法签名 → 实际不可用。
- `order:` 分支：`scopeAllows` 对 `order:` 永远 `false`（无对应分支）→ 即便能签发也下载不了，是半成品。
- 这是设计文档 §5.4（分级 TTL）与「`order:{orderNo}` 仅给购买该订单的用户」的承诺，但落地不完整。

**建议**：要么补齐签发路径与 `order:` 的 `scopeAllows`（绑定「该文件所属订单 + 购买者」校验），要么本期移除 `order:`/`role:admin` 的 TTL 配置与端点分支，标注为后续扩展，避免留下「配置项存在但无入口」的歧义。

---

## 🟠 中

### F-3 `FileLinkSigner` 的 `sign(fileId, userId, ttl)` 旧重载未被使用，且语义误导

**位置**：`FileLinkSigner.kt:41-43`

```kotlin
/** 仅供内部调用兼容，签发结果仍使用新的 user scope 载荷。 */
fun sign(fileId: UUID, userId: Long, ttlSeconds: Long): SignedLink =
    sign(fileId, "user:$userId", ttlSeconds)
```

全模块搜索 `sign(` 的调用方均使用新签名（`sign(stored.id, scope, ttlFor(scope))`），此重载无任何调用点。注释「仅供内部调用兼容」与实际不符，属于残留代码。

**建议**：删除该重载，或若保留作公开 API 则去掉「内部兼容」注释并补测试。

---

### F-4 `createDownloadLinks` 对重复 fileId 的去重依赖 `validateFileIds`，但 `associateBy` 在重复时仍可能踩坑

**位置**：`FileServiceImpl.kt:64-73`、`FileServiceImpl.kt:124-132`

`validateFileIds` 已做 `distinct().size != size` 的重复校验，逻辑正确。但 `findOwnedFiles` 用 `findAllByIdInAndOwnerId` 返回的列表 `size != fileIds.size` 来判定「是否存在缺失」——**如果数据库层面 `id` 因唯一约束不会重复，这成立**。StoredFile 的 `@Id` 是 UUID 主键，唯一性有保证，故当前安全。

记录此点仅为提醒：若未来 `findAllByIdInAndOwnerId` 的实现改为 `IN` 查询且 DB 出现脏数据（如同 id 多行，理论上不可能），`size` 比对会误判。**当前无实际风险**，保持即可。

---

### F-5 上传时 `sizeBytes` 取 `MultipartFile.size`，空文件可绕过 `CREATE_NEW` 的语义但仍落库

**位置**：`FileServiceImpl.kt:165`（`sizeBytes = multipartFile.size`）、`FileServiceImpl.kt:172-186`（`writeAndHash`）

- 空文件（0 字节）：`writeAndHash` 的 `while (input.read(buffer))` 立即返回 -1，写出 0 字节文件，sha256 为空串的摘要 `e3b0c442...`。逻辑无误，但 `Files.newOutputStream(... CREATE_NEW)` 在目标已存在时抛 `FileAlreadyExistsException`——由于文件名用 `UUID.randomUUID()`，碰撞概率极低，可接受。
- 真正问题：**`maxFileSizeBytes` 校验在 `validateUploadBatch` 中用 `file.size`，而 Spring 的 `MultipartFile.size` 在流式临时文件场景下可能尚未完全落地**。多数实现（如 `StandardMultipartFile`）在解析时已写盘并已知大小，问题不大；但若配置了 `resolve-lazily`，`size` 可能在校验时为 0 而实际内容超限。当前 `application.yaml` 未显式配置 lazy，默认安全。

**建议**：在 `writeAndHash` 内对已写字节数做 `> maxFileSizeBytes` 的二次校验（流式累计），作为对 lazy 解析的纵深防御。非阻塞。

---

### F-6 `downloadTokenTtlSeconds` 与 `signing.*TtlSeconds` 双轨配置，前者沦为默认兜底

**位置**：`FileProperties.kt:13`、`FileServiceImpl.kt:224`

- `downloadTokenTtlSeconds`（默认 300s）仅被 `ttlFor` 的 `else` 分支用作兜底 TTL，而所有已知 scope 都命中具体分支，`else` 不可达。
- `FileProperties.init` 仍校验 `downloadTokenTtlSeconds > 0`，`.env.example` 与 `application.yaml` 仍保留该配置项，但实际不影响任何签发路径。

**后果**：配置存在歧义——运维可能误以为调 `FILE_DOWNLOAD_LINK_TTL_SECONDS` 能改下载链接有效期，实际无效。

**建议**：将 `downloadTokenTtlSeconds` 标记为 `@Deprecated` 并注释「改用 `shopmall.file.signing.*`」，或直接删除并由 `signing.userTtlSeconds` 取代默认值。

---

## 🟡 低

### F-7 `StoredFile` 实体 `storage` 字段无 `@Column(name=...)`，依赖 Hibernate 命名策略

**位置**：`StoredFile.kt:48-50`

```kotlin
@Column(nullable = false, length = 16)
var storage: String = "local",
```

其他字段均显式指定 `name`（如 `original_filename`、`byte_size`），唯独 `storage` 依赖 SpringPhysicalNamingStrategy 转成 `storage`（恰好与迁移 SQL 的列名一致）。当前能用，但与同实体其他字段的显式风格不一致，且若命名策略变更会静默错列。

**建议**：补 `@Column(name = "storage", nullable = false, length = 16)`，与迁移 SQL `ALTER TABLE file_metadata ADD COLUMN IF NOT EXISTS storage` 对齐显式化。

---

### F-8 V2 迁移 `CREATE INDEX` 缺少 `created_at DESC` 方向，与设计文档/实体索引声明不完全一致

**位置**：`V2__extend_file_metadata.sql:15-16`、`StoredFile.kt:16-18`

- 迁移：`CREATE INDEX IF NOT EXISTS idx_file_metadata_owner_created ON file_metadata (owner_id, created_at)`
- 实体：`Index(... columnList = "owner_id, created_at")`
- 设计文档 §6 与待办：期望命中 `idx_file_metadata_owner_created` 支持 `owner_id + created_at DESC` 分页。

PostgreSQL 对 `(owner_id, created_at)` 的索引可同时服务 ASC 与 DESC 查询（双序扫描），功能正确。但 `findAllByOwnerIdOrderByCreatedAtDesc` 是 DESC 排序，若数据量大且排序方向固定，`DESC` 索引或 `NULLS LAST` 调优会更优。当前规模下非问题。

**建议**：保持现状即可；若未来分页性能成热点，再考虑 `CREATE INDEX ... (owner_id, created_at DESC)`。待办文档已正确记录需 `EXPLAIN` 验证。

---

### F-9 `deleteStoredFiles` 的 staging 恢复在「跨文件系统」下非原子，且异常包装为 `IllegalStateException` 可能吞掉原始业务异常类型

**位置**：`FileServiceImpl.kt:241-258`

- `stageForDeletion` 失败时抛 `IllegalStateException("Unable to stage file content for deletion.", ex)`，把原始异常（可能是 `IOException`）包装。`GlobalExceptionHandler` 对 `IllegalStateException` 的响应可能与 `ResourceNotFoundException` 等业务异常不同，需确认前端/调用方能正确区分。
- `moveFile` 在不支持 `ATOMIC_MOVE` 时退回普通 `Files.move`，若 `storageRoot` 与 `.deleting` 跨文件系统，普通 move 实际是 copy+delete，中途失败会留下半拷贝文件。当前 `.deleting` 在 `storageRoot` 子目录下，同文件系统，安全。

**建议**：异常包装可改为保留原异常类型或定义专用 `FileStorageException`；跨文件系统场景本期不存在，保持即可。

---

### F-10 设计文档承诺的多项能力未落地（设计 vs 实现差距）

**位置**：`docs/file-system-design.md` 全文 vs 当前实现

设计文档描述了完整的反刷/反遍历/S3/缓存体系，但代码中**均未实现**，且文档未明确标注「未实现 / 后续」：

| 设计承诺 | 当前状态 |
|---|---|
| `FileRateLimitService`（Redis Lua 令牌桶，上传/下载/链接/删除四热点限流） | ❌ 未实现，无任何速率控制 |
| `FileSlowBurnDetector`（慢刷检测 + 软封禁） | ❌ 未实现 |
| `StorageBackend` 抽象 + `LocalStorageBackend` / `S3StorageBackend` | ❌ 未实现，`FileServiceImpl` 仍直接操作 `java.nio.file`，`storage` 字段仅落库占位 |
| ContentType 白名单 + 魔数嗅探（反恶意上传） | ❌ 未实现，仅校验文件名长度/控制字符 |
| `FileMetaCache`（Redis 元数据缓存 + singleflight） | ❌ 未实现，每次下载 `findById` 落库 |
| per-file 下载计数告警（反遍历） | ❌ 未实现 |
| nonce 防重放（一次性链接） | ⚠️ 部分实现：签名含 nonce 但 `isValid` **不校验 nonce 是否已用过**，同一签名在 TTL 内可无限次重放 |

其中 **nonce 无防重放** 值得单独提示：设计文档 §4 明确「nonce + 短 TTL 防 URL 复用与重放」，但 `FileLinkSigner.isValid` 仅校验 nonce 非空且长度合法，不记录/校验已用 nonce。短 TTL（60-300s）是当前唯一的重放约束。

**建议**：
- 在设计文档顶部或各章节明确标注「已实现 / 待实现」状态，避免文档与代码割裂误导后续开发。
- nonce 防重放若本期不做，应在文档中降级表述为「nonce 仅作签名绑定，防重放依赖短 TTL」。
- 限流、S3、缓存等若为后续迭代，建议在 `file-database-test-backlog.md` 之外另建实现待办清单。

---

## 汇总

| 级别 | 编号 | 摘要 |
|---|---|---|
| 🔴 | F-1 | `user:` scope 下载鉴权强依赖密钥保密，无纵深防御；`order:` scope 校验缺失 |
| 🔴 | F-2 | `order:`/`role:admin` 可算 TTL 却无签发入口，死代码 |
| 🟠 | F-3 | `FileLinkSigner.sign(fileId, userId, ttl)` 旧重载无调用点 |
| 🟠 | F-4 | `findOwnedFiles` size 比对依赖主键唯一性（当前安全，记录） |
| 🟠 | F-5 | `maxFileSizeBytes` 仅前置校验 `file.size`，无流式二次校验 |
| 🟠 | F-6 | `downloadTokenTtlSeconds` 沦为不可达兜底，配置歧义 |
| 🟡 | F-7 | `storage` 字段缺 `@Column(name=...)`，与实体风格不一致 |
| 🟡 | F-8 | V2 索引方向与 DESC 查询非最优（功能正确） |
| 🟡 | F-9 | 删除 staging 异常包装为 `IllegalStateException`，可能吞业务异常类型 |
| 🟡 | F-10 | 设计文档大量承诺未落地；nonce 无防重放 |

**优先处理**：F-2（死代码 / 半成品 scope，影响最直接的可用性与可维护性）、F-10 中的 nonce 防重放表述（安全语义偏差）。F-1 在密钥保密前提下非紧急，但应补充文档说明与轮换机制。

---

## 修复记录（2026-07-26）

按严重程度由高到低修复。审查时 F-3、F-6 的初判与设计文档契约存在出入，核对后修正处理方式。

| 编号 | 处理 | 说明 |
|---|---|---|
| F-1 | ✅ 已处理（注释说明） | `scopeAllows` 上方补注释：明确 `user:` scope 下载鉴权强依赖 HMAC 密钥保密，需配套密钥轮换；`order:*` 为扩展点暂无签发入口。代码逻辑无改动（密钥保密前提下安全）。 |
| F-2 | ⚠️ 保留扩展点 + 注释标注 | 核对设计文档：`order:`/`role:admin` 与 `secure-download` 端点是为后续分级 TTL 预留的扩展点，删除会偏离设计。改为在 `ttlFor` 注释明确「暂未提供签发入口，仅供 secure-download 验签扩展」。不删除配置与端点。 |
| F-3 | ✅ 已处理（注释修正） | 核对设计文档 §3/§5：旧重载 `sign(fileId, userId, ttl)` 是「保留的内部向后兼容糖」，**设计意图即为保留**，非死代码。注释从「仅供内部调用兼容」改为引用设计文档，消除误导。 |
| F-6 | ✅ 已修复（补齐回退契约） | **初判修正**：设计文档 §5.4 明确 `download-token-ttl-seconds` 应作为 `user-ttl` 的回退别名，但原代码未实现回退（`userTtlSeconds` 固定默认 300，忽略 `downloadTokenTtlSeconds`）。修复：`SigningProperties.userTtlSeconds` 默认改为哨兵 `-1`，新增 `resolvedUserTtl(downloadTokenTtlSeconds)`；`ttlFor` 的 `user:` 与 `else` 分支改用该方法回退。补测试 `user scope ttl falls back to download token ttl when user ttl is unset` 验证回退生效。 |
| F-7 | ✅ 已修复 | `StoredFile.storage` 补 `@Column(name = "storage", nullable = false, length = 16)`，与 V2 迁移列名及同实体显式命名风格对齐。 |
| F-4 | ⏭️ 保持现状 | 依赖主键唯一性，当前安全，无需改动。 |
| F-5 | ⏭️ 保持现状 | 流式二次校验为纵深防御，非阻塞；默认非 lazy 解析下无风险。 |
| F-8 | ⏭️ 保持现状 | 索引功能正确，待办文档已记 `EXPLAIN` 验证。 |
| F-9 | ⏭️ 保持现状 | 同文件系统下原子移动安全；异常包装非阻塞。 |
| F-10 | 📌 待办 | 设计文档未标注实现状态、nonce 无防重放等问题仍存在，建议后续单独排期。文档状态标注可后续补。 |

**验证**：`./gradlew test` 文件模块三个测试类（FileLinkSignerTest / FileServiceImplTest / FileControllerTest）全部通过，含新增回退测试。

**未修复项建议**：
- F-10 中的 **nonce 防重放**：当前 `isValid` 不校验 nonce 是否已用过，重放仅靠短 TTL。若要兑现设计文档「防重放」，需引入 Redis 记录已用 nonce（TTL = 链接 TTL）。这是较大的改动，建议单独排期。
- F-10 中的 **设计文档状态标注**：建议在 `file-system-design.md` 各章节加「已实现 / 待实现」标记，消除文档与代码割裂。
