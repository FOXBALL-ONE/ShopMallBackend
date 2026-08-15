# 文件模块能力与迁移设计

> 文档状态：现状梳理与迁移设计
> 适用范围：ShopMall 后端通用文件、商品图片、工单附件
> 目标：在保留现有外部接口、下载授权和文件关联关系的前提下，将本地文件系统存储迁移到可替换的对象存储后端。

## 1. 结论摘要

当前文件模块不是简单的静态目录暴露，而是一个由数据库元数据、受控本地文件路径和短期 HMAC 下载链接组成的文件域。

```text
上传客户端
  -> FileController / ProductImageController
  -> FileService
  -> 本地 storage/YYYY/MM/DD/{uuid}.{ext}
  -> file_metadata

下载客户端
  -> 带 scope、expires、nonce、signature 的应用链接
  -> FileLinkSigner 验签
  -> 文件元数据与授权范围校验
  -> 本地文件流
```

迁移的推荐边界是：保留 `StoredFile`、`FileService`、现有 API 路由和应用层 HMAC 签名；仅将 `FileServiceImpl` 中的本地读写逻辑抽象为存储后端，并利用现有 `storage` 与 `relative_path` 字段进行双读和分批切换。

## 2. 当前模块能力

### 2.1 通用文件 API

| 能力 | API | 授权与结果 |
|---|---|---|
| 上传 | `POST /api/files` | JWT 用户上传一个或多个 `files`，返回文件元数据和短期下载链接 |
| 我的文件 | `GET /api/files/mine` | JWT 用户分页查看本人文件；每次响应重新签发下载链接 |
| 批量刷新链接 | `POST /api/files/batch/links` | 仅允许为本人文件签发 `user:{id}` 或 `public` scope 的链接 |
| 匿名签名下载 | `GET /api/files/{file_id}/download` | 仅验证 HMAC、到期时间和 scope；适用于短期 bearer 链接 |
| 认证安全下载 | `GET /api/files/{file_id}/secure-download` | JWT 用户或管理员下载 `role:*`、`order:*` 等未来受保护 scope |
| 单个删除 | `DELETE /api/files/{file_id}` | 仅文件所有者可删除 |
| 批量删除 | `DELETE /api/files/batch` | 所有文件均需属于当前用户；ID 不允许重复 |

通用下载响应包含 `Content-Disposition: attachment`、原始文件名、实际或回退的 Content-Type，以及 `X-Content-Type-Options: nosniff`。

### 2.2 商品图片能力

商品图片走独立入口：

| 能力 | API | 行为 |
|---|---|---|
| 管理端上传 | `POST /admin/api/product-images` | 仅管理员；单次最多 20 个；仅接受 JPEG、PNG、WebP、GIF |
| 公开稳定图片地址 | `GET /api/product-images/{file_id}?signature=...` | 验证长期用途签名后，302 跳转到新签发的短期 `public` 下载链接 |

商品实体保存的是图片 URL 字符串，并不直接持有 `StoredFile` 外键。商品图片稳定地址的签名由 `FILE_SIGNING_SECRET` 与 `product-image:{fileId}` 派生，当前没有过期时间。

### 2.3 工单附件能力

工单消息复用通用 `FileService.upload`：

- 客户或管理员发送工单消息时可上传附件；
- 工单模块先执行消息幂等、速率限制、单消息、单工单和单客户附件配额校验；
- `support_ticket_message_attachments.file_id` 通过外键关联 `file_metadata.id`；
- 工单详情返回时签发 `support-ticket` scope 的短期下载链接；
- 上传请求在 Multipart 解析前由过滤器按请求总长度限制，避免超大请求占用解析资源。

工单附件有数据库外键保护，已被工单消息引用的文件不能被通用删除接口删除。商品图片没有同等外键保护。

## 3. 当前数据模型与存储布局

### 3.1 `file_metadata` 是文件域的权威元数据

`StoredFile` 对应表 `file_metadata`，文件二进制不存数据库。迁移必须保留以下字段的语义：

| 字段 | 当前含义 | 迁移要求 |
|---|---|---|
| `id` | 应用侧生成的 UUID，所有下载链接和附件关联的主键 | 必须保持不变 |
| `owner_id` | 文件所有者，用于管理和签发用户链接 | 必须保持不变 |
| `original_filename` | 下载响应使用的原始文件名 | 必须保持不变 |
| `stored_filename` | UUID 加安全扩展名，当前唯一 | 保持或作为对象键的一部分 |
| `relative_path` | 相对存储根目录的受控路径 | 推荐直接复用为对象键 |
| `content_type` | 上传端声明的 MIME 类型 | 原样保留；不应仅据此信任内容安全性 |
| `byte_size` | 文件大小 | 迁移后与对象大小核验 |
| `sha256` | 上传流式计算的内容摘要 | 迁移完整性校验的基准 |
| `storage` | 当前为 `local`，预留存储后端路由 | 切换为目标后端标识，例如 `s3` |
| `created_at` | 文件创建时间 | 必须保持不变 |

本地文件默认路径规则为：

```text
{FILE_STORAGE_PATH}/{UTC 年}/{UTC 月}/{UTC 日}/{uuid}.{安全扩展名}
```

例如：`storage/2026/08/14/550e8400-e29b-41d4-a716-446655440000.webp`。

### 3.2 关联关系

```text
file_metadata
  <- support_ticket_message_attachments.file_id  (数据库外键)

products_images.image_url
  -> /api/product-images/{fileId}?signature=...  (URL 字符串，无外键)
```

迁移清单不能只依赖外键：商品图片 URL 中包含文件 UUID，但数据库没有可查询的 `StoredFile` 关联。迁移前应解析 `products_images.image_url`，核对其引用的文件 UUID 是否存在于 `file_metadata` 和目标对象存储中。

## 4. 当前安全与一致性设计

### 4.1 上传校验

- 单文件大小受 `FILE_MAX_FILE_SIZE_BYTES` 限制，默认 100 MiB；
- 单批数量受 `FILE_MAX_BATCH_SIZE` 限制，默认 20；
- 原始文件名剥离路径分隔符，拒绝空名、控制字符和超过 255 字符的名称；
- 存储文件名使用 UUID，扩展名仅保留 1 到 10 位字母或数字；
- 写入时流式计算 SHA-256；
- 商品图片额外限制声明的 Content-Type 为 JPEG、PNG、WebP 或 GIF；
- 工单附件还受到请求总大小、消息、工单和客户累计配额限制。

当前通用文件服务不做内容嗅探、反病毒扫描或图片解码验证。迁移到对象存储不会自动解决这些问题；如目标环境要求更高安全性，应在上传链路增加异步扫描和隔离状态，而不是直接放宽下载权限。

### 4.2 路径与删除一致性

- 所有本地路径都从 `storageRoot.resolve(relativePath).normalize()` 取得，并检查仍位于根目录内，避免路径穿越；
- 上传先写入文件，再 `saveAllAndFlush` 元数据；若数据库事务回滚，会删除已写入的内容；
- 删除先将内容移动至 `storage/.deleting/{fileId}-{random}`，数据库删除或 flush 失败时恢复内容；成功后清理暂存文件；
- 本地移动优先使用原子移动，不支持时回退普通移动。

对象存储通常没有本地原子移动语义。迁移后的实现需要通过“先复制或写入目标、校验、提交元数据、延迟删除源对象”的流程替代上述补偿行为。

### 4.3 下载授权

下载链接的签名载荷为：

```text
fileId:scope:expiresAtEpochSeconds:nonce
```

使用 `HmacSHA256` 生成 URL-safe Base64 签名。校验使用常量时间比较，签名绑定文件、scope、到期时间和随机 nonce。

| scope | 当前签发方 | 默认 TTL | 下载条件 |
|---|---|---:|---|
| `public` | 用户批量刷新接口、商品图片重定向 | 60 秒 | 有效 HMAC 即可 |
| `user:{ownerId}` | 上传、我的文件、批量刷新 | 300 秒或 `FILE_SIGN_USER_TTL` | 有效 HMAC；下载端不要求 JWT |
| `support-ticket` | 工单详情 | 300 秒或 `FILE_SIGN_USER_TTL` | 有效 HMAC；下载端不要求 JWT |
| `role:admin` | 暂无签发入口 | 180 秒 | `secure-download` 中需要管理员 JWT |
| `order:{id}` | 暂无签发入口 | 300 秒 | 当前不允许打开，待补充订单归属校验 |

`/api/files/{id}/download` 是匿名可访问端点，因此 `public`、`user:*` 和 `support-ticket` 当前都是短期 bearer 链接。迁移时必须保留“签名密钥保密、短 TTL、scope 不可篡改”的安全前提，不能把对象存储桶直接设置为公开读取。

## 5. 迁移目标架构

推荐引入存储后端抽象，而不改变控制器或领域服务对外契约：

```text
FileController / ProductImageController / SupportTicketService
  -> FileService
  -> FileObjectStorage
       |- LocalFileObjectStorage       (现有实现)
       |- S3CompatibleObjectStorage    (迁移目标)
  -> file_metadata

下载：应用 HMAC 验签和 scope 校验
  -> 目标对象存储短期预签名 GET，或应用代理流式输出
```

建议的后端能力如下：

| 能力 | 建议抽象 | 本地实现 | 对象存储实现 |
|---|---|---|---|
| 写入 | `put(key, input, metadata)` | 临时文件后原子移动 | 上传到目标 key，附加 Content-Type、SHA-256 元数据 |
| 读取 | `open(key)` 或 `presignGet(key, ttl)` | `Path` / 文件流 | SDK 流或短期预签名 GET |
| 存在检查 | `exists(key)` | `Files.isRegularFile` | HEAD Object |
| 校验 | `contentLength`、`sha256` | 读取或已有属性 | HEAD 元数据或流式重新计算 |
| 删除 | `delete(key)` | 暂存后删除 | 延迟删除或版本化删除标记 |

继续由应用层签发和校验 `/api/files/.../download` 链接。验证通过后可以返回对象存储的更短期预签名 URL（302），或由应用代理对象流。前者降低应用带宽压力，后者更容易统一下载头和审计；两者都不能绕过应用层 scope 校验。

## 6. 兼容性要求

### 6.1 API 兼容

以下接口和响应字段应保持不变：

- `/api/files`、`/api/files/mine`、`/api/files/batch/links`；
- `/api/files/{file_id}/download` 与 `/secure-download` 的参数格式；
- 上传响应和文件列表中的 `id`、`file_name`、`content_type`、`size_bytes`、`sha256`、`signed_download_url`、`download_expires_at`、`scope`、`storage`；
- 商品图片稳定地址 `/api/product-images/{file_id}?signature=...`。

### 6.2 签名密钥兼容

短期下载链接最长只需兼容其已签发 TTL；切换后保留旧 `FILE_SIGNING_SECRET` 至少覆盖最长 TTL 即可。

商品图片签名不同：它没有到期时间，且由同一个 `FILE_SIGNING_SECRET` 派生。若更换该密钥，历史商品图片 URL 会立即失效。迁移必须选择其一：

1. 保持现有密钥；
2. 在产品图片解析处支持新旧双密钥验证；
3. 重新生成所有商品图片 URL 并更新 `products_images.image_url`。

在未完成其中一项前，不应轮换文件签名密钥。

### 6.3 元数据兼容

- 不改变 `file_metadata.id`、`relative_path`、`sha256`、`owner_id` 和附件外键；
- 推荐沿用现有 `relative_path` 作为对象 key，避免额外映射表；
- `storage` 作为路由字段，迁移成功后才从 `local` 更新为目标后端标识；
- 不能删除仍被 `support_ticket_message_attachments` 引用的元数据或对象；
- 商品图片因无外键，需要在删除策略中单独扫描 URL 引用或增加后续显式关联能力。

## 7. 分阶段迁移方案

### 阶段 0：盘点与冻结基线

1. 记录 `file_metadata` 总数、按日期与 `storage` 的分布、总 `byte_size`、每个 SHA-256；
2. 扫描本地存储根目录，识别“有元数据无文件”和“有文件无元数据”的差异；
3. 解析商品图片 URL，核对其中 UUID 与 `file_metadata` 的存在性；
4. 统计工单附件外键数量，确认所有引用文件均可读取；
5. 对数据库和本地存储做可恢复备份或快照；
6. 在开始复制后保留本地存储为只读源，直至完成回滚观察期。

### 阶段 1：部署双读能力

1. 增加对象存储实现和按 `StoredFile.storage` 选择后端的路由；
2. 保持 `local` 读取行为不变；
3. 为目标后端增加读取、HEAD、上传、删除和错误映射测试；
4. 保持下载 HMAC 与应用端点不变，避免客户端迁移；
5. 先不将新上传流量切换到目标后端。

### 阶段 2：回填历史对象

对每条 `storage=local` 的元数据按如下幂等流程处理：

```text
读取本地 relative_path
  -> 校验本地文件存在、大小和 SHA-256
  -> 上传至目标对象 key=relative_path
  -> HEAD 校验对象大小与保存的 SHA-256
  -> 更新 file_metadata.storage=目标后端
  -> 保留本地源文件
```

应使用 UUID 或 `relative_path` 作为任务幂等键；已存在的目标对象先进行大小和摘要比对，不匹配时停止并转人工处理，不能盲目覆盖。

### 阶段 3：灰度切换写入

1. 选择少量内部用户或管理员商品图片上传流量写入目标后端；
2. 新元数据直接标记为目标 `storage`；
3. 通过现有上传、列表、签发链接、下载、商品图片重定向和工单附件页面做端到端验证；
4. 监控对象读取失败、签名失败、下载 404、大小不一致、SHA-256 不一致和对象存储延迟；
5. 灰度稳定后切换全部新上传。

### 阶段 4：切换读取与清理本地副本

1. 确认所有元数据均已迁移或已登记异常；
2. 再次抽样并全量核对对象数量、大小和 SHA-256；
3. 保留本地副本至少覆盖业务观察期、最长链接 TTL 和备份验证周期；
4. 使用独立的清理任务删除已验证、已迁移且不在回滚窗口内的本地文件；
5. 清理任务必须同样跳过仍有元数据或附件关系异常的对象，并输出可审计清单。

## 8. 回滚策略

| 故障阶段 | 回滚动作 |
|---|---|
| 回填时对象写入或校验失败 | 不更新 `storage`；保留本地源文件；记录 UUID 和失败原因 |
| 灰度读失败 | 将受影响元数据的路由恢复为 `local`；对象保留以便排查 |
| 新写入目标后端失败 | 让上传失败并返回可重试错误，或在明确实现双写补偿后回退本地；不能伪造成功响应 |
| 密钥轮换导致商品图失效 | 恢复旧密钥或启用旧密钥验证；禁止仅依赖对象副本修复 |
| 误删除本地副本 | 从对象存储按 `relative_path` 与 SHA-256 恢复；因此清理前必须完成对象校验 |

迁移期间不能将对象存储当作唯一副本，直到数据核对、回滚演练和观察期全部完成。

## 9. 验收清单

- [ ] `file_metadata` 的 UUID、owner、文件名、相对路径、大小、摘要和创建时间保持一致；
- [ ] 目标对象数量、大小和 SHA-256 与已迁移元数据一致；
- [ ] 通用文件上传、列表、刷新链接、删除和下载 API 无客户端协议变化；
- [ ] 商品图片稳定 URL 在切换及签名密钥策略后仍可访问；
- [ ] 工单附件关系、下载权限和配额行为保持不变；
- [ ] 公开对象桶未被开启为全局匿名读取；
- [ ] HMAC 签名、scope、TTL 和 `nosniff` 下载头仍由应用层执行；
- [ ] `local` 与目标后端双读、回滚和对象完整性测试通过；
- [ ] 本地副本清理前完成备份、抽样验证和回滚演练。

## 10. 本次文档边界

本文用于文件模块迁移评估与实施设计，不修改现有文件存储实现，不创建数据库迁移脚本。实际接入 S3、MinIO 或其他对象存储时，应以本文的兼容性要求为约束，另行实现存储适配器、双读路由、回填任务和相应测试。
