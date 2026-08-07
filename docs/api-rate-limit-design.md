# 全局 API 限速设计

## 1. 目标与结论

为业务 API 增加全局、分布式一致的滑动窗口限速：

| 请求身份 | 统计维度 | 默认规则 |
| --- | --- | --- |
| 已登录用户 | `user_id` | 任意连续 60 秒最多接受 10 个请求 |
| 未登录用户 | 可信解析后的客户端 IP | 任意连续 60 秒最多接受 5 个请求 |

管理员可在 `AdminPanelUI` 修改限速开关、两项额度和免限速路径列表，保存后无需重启应用、无需等待本地缓存过期；所有后端节点对后续请求使用 Redis 中的新配置。实现基于项目现有的 `StringRedisTemplate` 和 Lua 原子脚本，不引入第三方限流网关或新数据库表。

本方案不创建或修改数据库迁移。运行时配置保存在 Redis，`application.yaml` 仅提供 Redis 尚未保存配置时的部署默认值。

### 本次优化摘要

本次修订在原有“10/5 次、60 秒滑动窗口、Redis 动态设置”的基础上增加并优化：

1. 管理端可完整替换的 `excluded_path` 路径列表，命中路径跳过本全局限速器但不跳过鉴权。
2. 严格路径语法、敏感前缀拒绝与 Spring `PathPattern` 匹配，避免一个宽泛或编码路径规则变成绕过通道。
3. 热路径由 `HGETALL` 收敛为 `HMGET`，仅缓存版本化的路径匹配器，不缓存额度值，保持跨节点即时刷新。
4. 下调额度时，Lua 返回“首次可成功”的 `Retry-After`，避免客户端按最早一条过期时间反复收到无效 `429`。
5. 使用 `settings_id` 区分 Redis 清空后的配置代际，并限制本地缓存只保留一份快照，避免规则错用和缓存增长。
6. 明确过滤器单次注册、Redis 内存策略、代理 IP 信任链与动态规则测试边界。
7. 增加可热更新的 `enabled` 开关；关闭时保留鉴权和其他安全边界，但跳过本方案的路径匹配、身份解析、桶读写和额度响应头。

## 2. 范围与语义

### 2.1 纳入统计的请求

限速器覆盖 `/api/**` 与 `/admin/api/**` 下的所有业务请求，包括登录、注册、公开查询、管理端操作，以及最终会返回 `401` 或 `403` 的请求。

| 请求 | 是否计入 | 原因 |
| --- | --- | --- |
| `OPTIONS /**` | 否 | CORS 预检不是业务请求。 |
| `/actuator/health`、`/actuator/info`、`/error` | 否 | 健康检查和框架错误派发必须可用。 |
| `POST /webhook`、`POST /api/logistics/webhook/**` | 否 | 外部回调有独立的签名、请求体大小与幂等保护，不能因本方案丢失支付或物流事件。 |
| `GET`、`PUT /admin/api/rate-limit-settings` | 是 | 管理配置接口没有限速旁路，管理员同样受登录用户额度约束。 |
| `PUT/PATCH /admin/api/rate-limit-settings/enabled` | 是 | 专用开关接口也受当前登录用户额度约束；关闭后仍可通过鉴权访问并重新开启。 |

请求带有已验证的 access JWT 时，按用户桶计数；没有 JWT、JWT 过期或 JWT 非法时，按匿名 IP 桶计数。攻击者不能通过附加非法 Bearer 令牌绕开匿名用户每分钟 5 次的限制。

### 2.2 严格的“一分钟”定义

对身份主体 `s`，在时刻 `t` 统计半开区间 `(t - 60 秒, t]` 内**已接受**的请求集合 `A(s, t)`：

```text
已登录：|A(userId, t)| <= authenticated_requests_per_minute
未登录：|A(clientIp, t)| <= anonymous_requests_per_minute
```

这是滑动窗口而非按整分钟对齐的固定窗口。例如用户在 `12:00:59` 已发送 10 次请求，不能在 `12:01:00` 立刻再发送 10 次；前一批请求在各自超过 60 秒前仍会占用额度。被拒绝的请求不写入窗口，因此持续重试不会延长锁定时间。

v1 将窗口长度固定为 60 秒，管理面板只允许修改两类请求的最大次数。这样“每分钟”的产品语义保持稳定，`Retry-After` 的计算也不会产生歧义。

### 2.3 可配置的免限速路径列表

除第 2.1 节的固定系统排除规则外，管理员可以传入 `excluded_path` 列表，为指定业务路径配置免限速规则。命中规则的请求不读取或写入用户/IP 桶，不返回 `X-RateLimit-*` 额度头，随后继续现有的认证、授权和控制器处理；**免限速不等于免鉴权**。

路径列表是完整替换，不是增量追加：一次 `PUT` 提交的列表就是新的全部动态规则；未传入任何 `excluded_path` 即清空动态规则。固定系统排除规则不显示在该列表中，也不能被管理端删除。

为避免把一个宽泛模式意外变成全局绕过，v1 只允许以下规则语法：

| 规则类型 | 示例 | 匹配范围 |
| --- | --- | --- |
| 精确路径 | `/api/files/public` | 只匹配该路径，忽略 query string。 |
| 子树路径 | `/api/catalog/**` | 匹配 `/api/catalog` 及其子路径。 |

路径规则按 Spring `PathPatternParser` 语义编译，不能用字符串 `startsWith` 自行判断。过滤器先调用 `ServletRequestPathUtils.parseAndCache(request)`，再以去除 context path 后、不含 query string 的 `RequestPath` 作为匹配目标；不能自行 URL 解码或重新拼接 URI。对于含矩阵参数、百分号、反斜杠、重复斜杠或控制字符的非规范请求路径，动态规则一律不命中，仍进入限速判断。服务端对输入执行规范化、去重和字典排序，再写入 Redis；匹配对象只在规则版本变化时重新编译。规则必须满足以下限制：

- 以 `/api/` 开头，单条长度不超过 160 字符；
- 不允许空串、空白/控制字符、`?`、`#`、`;`、`%`、反斜杠、`..`、重复斜杠、路径变量或除末尾 `/**` 以外的通配符；
- 不允许 `/api/**`、`/api/auth`、`/api/auth/**`、`/api/logistics/webhook`、`/api/logistics/webhook/**`、`/admin/**`、`/actuator/**`、`/error`；
- 最多 20 条，规范化后总长度最多 2000 字符；精确路径和子树路径均按所有 HTTP 方法生效。

精确路径不得以 `/` 结尾；子树路径只能以 `/**` 结尾，前缀不得以 `/` 结尾。不会把 `/api/example/` 自动改写为 `/api/example`，请求路径尾斜杠的匹配语义由 `PathPattern` 保持明确。服务端必须按“精确等于或路径分段前缀”检查禁用路径，不能以字符串相似匹配替代，例如 `/api/authentication` 不应因名称相近被误拒绝。20 条和 2000 字符的上限是为重复 query 参数在浏览器、反向代理和应用服务器中的可靠传输预留边界，而不是 Redis 的存储限制。限制认证、外部回调与管理端路径是有意的：登录、令牌、第三方事件和控制面不能因一次后台误操作失去防护。确有此类特殊需求时，应新增经代码审查的固定排除规则，而不是开放为管理面板的动态绕过能力。

## 3. 与现有项目的衔接

| 现有组件 | 已有能力 | 本方案的用法 |
| --- | --- | --- |
| `authentication/JwtAuthenticationFilter.kt` | 已验证的 access JWT 会把数值型用户 ID 写入 `SecurityContextHolder`。 | 限速器直接区分用户桶与匿名桶，不重复解析 JWT 或查询数据库。 |
| `SupportTicketRequestProtection`、`OrderIdempotencyService` | 已通过 `StringRedisTemplate` + Lua 做 Redis 原子操作。 | 复用同一技术和编码风格。 |
| `shared/ResponseBuilder.kt` | 已支持统一响应体和 `Retry-After` 响应头。 | 过滤器短路时直接写同样的 JSON 响应体。 |
| `config/SecurityConfig.kt` | JWT 过滤器已在 Spring Security 链中注册，`Retry-After` 已暴露给 CORS 客户端。 | 将限速过滤器插到 JWT 之后，并暴露新增额度响应头。 |
| `AdminPanelUI` | 已有 Naive UI 默认布局、菜单和 API composable 模式。 | 新增独立限速设置页与 API composable。 |

## 4. 算法选择

每个限速身份在 Redis 使用一个有序集合（sorted set），评分为 Redis 服务器时间毫秒值，成员为服务端生成的 UUID。每次决策由 Lua 脚本原子完成“清理过期记录、计数、允许或拒绝、写入新记录”。

| 方案 | 不采用原因 |
| --- | --- |
| 固定窗口 `INCR` + `EXPIRE` | 可在分钟边界突发：一分钟末尾 10 次加下一分钟开头 10 次，不满足连续一分钟最多 10 次。 |
| 令牌桶 | 天然允许突发，不严格保证任意 60 秒内的最大请求数。 |
| JVM 内存计数器 | 多实例会做出不同判断，重启会丢失状态。 |
| 仅 API 网关限速 | 当前仓库已拥有认证、Redis、统一响应和管理面板；在应用内实现更贴合现有部署与测试方式。 |

### 4.1 Redis 键

```text
rate-limit:settings:v1
rate-limit:v1:user:{userId}
rate-limit:v1:anonymous:{hmacClientIp}
```

`rate-limit:settings:v1` 是设置哈希。用户或匿名桶是 sorted set；空闲桶会自然过期，桶中最多保留当前一分钟内已接受的请求数。

匿名 IP 不直接出现在 Redis key 或日志中。`hmacClientIp` 使用独立密钥计算稳定的 `HMAC-SHA-256(clientIp, rateLimitIdentitySecret)` 前 128 位（32 个小写十六进制字符）。该密钥不得复用 JWT 签名密钥。

### 4.2 原子滑动窗口脚本

每次脚本只访问一个桶 key，因此未来接入 Redis Cluster 时不会出现跨槽 Lua 问题。动态设置先以单条 `HMGET` 读取限速所需字段，再把当前有效额度作为脚本参数传入。

时间由 Redis `TIME` 提供，不依赖应用节点时钟；多节点在窗口边界的判断完全一致。

```lua
-- KEYS[1] = 一个身份桶
-- ARGV[1] = windowMillis，固定 60000
-- ARGV[2] = 当前有效额度
-- ARGV[3] = 服务端生成的请求 UUID

local nowParts = redis.call('TIME')
local now = tonumber(nowParts[1]) * 1000 + math.floor(tonumber(nowParts[2]) / 1000)
local windowMillis = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

-- 保留 (now - 60s, now]；恰好 60 秒前的记录不再计数。
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - windowMillis)
local count = redis.call('ZCARD', KEYS[1])

if count >= limit then
    -- 下一次请求要被接受，现有 count 条中至少要移除 count - limit + 1 条。
    -- 取其中最后一条的过期时刻，额度下调后也能给出真正可重试的时间。
    local releaseRank = count - limit
    local releaseEntry = redis.call('ZRANGE', KEYS[1], releaseRank, releaseRank, 'WITHSCORES')
    local retryAfter = math.max(1, math.ceil((tonumber(releaseEntry[2]) + windowMillis - now) / 1000))
    -- 拒绝请求不刷新 TTL，避免攻击流量永久保留已经闲置的桶。
    return { 0, limit, 0, retryAfter }
end

redis.call('ZADD', KEYS[1], now, ARGV[3])
-- 多留 1 秒覆盖毫秒舍入；下次请求仍会用 ZREMRANGEBYSCORE 精确清理。
redis.call('PEXPIRE', KEYS[1], windowMillis + 1000)
return { 1, limit, limit - count - 1, 0 }
```

返回值为 `(allowed, limit, remaining, retryAfterSeconds)`。Redis 对该脚本串行执行，所以同一用户或 IP 的并发请求最多只有配置额度数量能被接受。

## 5. 身份与 IP 解析

限速过滤器位于 `JwtAuthenticationFilter` 后，身份判定顺序如下：

1. `SecurityContext` 中存在已认证的 `Long` principal 时，使用 `user:{userId}` 和登录用户额度；
2. 否则解析并规范化客户端 IP，使用 `anonymous:{hmacClientIp}` 和匿名额度。

新增独立的 `ClientIpResolver`，不可直接信任任意请求携带的 `X-Forwarded-For`。

| 连接情况 | 客户端 IP 来源 |
| --- | --- |
| 直连，或 `remoteAddr` 不在 `trusted-proxy-cidrs` | 使用 `HttpServletRequest.remoteAddr`，忽略所有转发头。 |
| 直连地址是受信任反向代理 | 优先解析 RFC 7239 `Forwarded`，没有时再兼容 `X-Forwarded-For`；从右向左去除受信任代理跳点，取第一个合法的非代理地址。 |
| 转发头缺失、格式非法，或链中只剩受信任代理 | 回退到 `remoteAddr`。 |

IPv4 与 IPv6 必须在哈希前规范化。生产部署中，`trusted-proxy-cidrs` 只能填写外部客户端无法直连的负载均衡器或反向代理网段；否则客户端可伪造转发头并无限制造匿名身份来绕过 5 次限制。

## 6. 动态设置与刷新语义

### 6.1 启动默认配置

新增 `RateLimitProperties` 和 `RateLimitConfig`。下面的 YAML 是配置形状；`RATE_LIMIT_IDENTITY_HASH_SECRET` 必须由环境变量或非提交的 `.env` 提供，不能使用空值或示例值上线。

```yaml
shopmall:
  rate-limit:
    enabled: "${RATE_LIMIT_ENABLED:true}"
    filter-enabled: "${RATE_LIMIT_FILTER_ENABLED:true}"
    window-seconds: 60
    default-authenticated-requests-per-minute: "${RATE_LIMIT_AUTHENTICATED_REQUESTS_PER_MINUTE:10}"
    default-anonymous-requests-per-minute: "${RATE_LIMIT_ANONYMOUS_REQUESTS_PER_MINUTE:5}"
    identity-hash-secret: "<由部署环境注入，至少 32 个字符>"
    default-excluded-paths: []
    trusted-proxy-cidrs: []
```

同一 `shopmall.rate-limit` 节点增加 `default-excluded-paths: []`。它是 Redis 尚未保存运行时设置时的免限速路径回退列表，也必须通过第 2.3 节的规则校验。

`RateLimitProperties` 在启动时验证：

- `windowSeconds == 60`；
- `filterEnabled == false` 时完全不注册过滤器；为保证后续可由管理端开启，过滤器注册时仍要求独立身份哈希密钥有效；
- 两项默认额度都在 `1..1000`；
- `defaultExcludedPaths` 使用第 2.3 节的同一套规则校验；
- `identityHashSecret` 至少 32 个字符；
- 每个 `trustedProxyCidrs` 项都是合法 CIDR。

YAML/环境变量是 Redis 没有动态设置时的回退默认值，其中 `enabled` 的默认值由 `RATE_LIMIT_ENABLED` 提供。第一次成功保存管理设置后，Redis 中的 `enabled` 与 `excluded_paths`（即使为空）会完整覆盖部署默认值；修改环境变量仍需要部署重启；运行中的开关、额度和路径列表必须经管理端 API 修改。`filter-enabled=false` 是部署/测试级静态保护，会完全卸载过滤器，不能由 Redis 或管理端开关重新装载。

### 6.2 Redis 设置哈希

首次由管理员保存后，`rate-limit:settings:v1` 写入以下字段：

| 字段 | 示例 | 用途 |
| --- | --- | --- |
| `enabled` | `true` | 运行时是否执行全局 API 限速；`false` 时保留鉴权但不读写限速桶。 |
| `authenticated_requests_per_minute` | `10` | 实时单用户额度。 |
| `anonymous_requests_per_minute` | `5` | 实时单 IP 额度。 |
| `excluded_paths` | `/api/catalog/**\n/api/files/public` | 规范化后的动态免限速路径，每行一条；空字符串表示无动态规则。 |
| `settings_id` | `a4f1...` | 每次从“无运行时设置”创建 hash 时生成的 UUID，用于区分 Redis 清空/恢复后的设置代际，不对外暴露。 |
| `version` | `3` | 单调递增的乐观锁版本。 |
| `updated_at` | `2026-08-07T11:30:00` | `LocalDateTime`，按 `ISO_LOCAL_DATE_TIME` 序列化。 |
| `updated_by` | `42` | 修改者管理员 ID。 |

哈希不存在时，有效设置为 YAML 默认值，`version = 0`，`source = DEFAULT`。哈希存在且合法时，`source = REDIS`。如果哈希存在却字段缺失、范围非法或类型无法解析，应返回 `503` 并记录错误，不能静默回退到更宽松的默认规则。

更新使用 Lua 比较并设置（CAS），避免两个管理页面互相覆盖。服务层在调用脚本前生成 `newSettingsId = UUID.randomUUID().toString()`；已有 hash 时脚本保留既有 `settings_id`，hash 不存在时才写入该新值：

```lua
-- KEYS[1] = rate-limit:settings:v1
-- ARGV[1] = expected version
-- ARGV[2] = enabled
-- ARGV[3] = authenticated requests per minute
-- ARGV[4] = anonymous requests per minute
-- ARGV[5] = excluded_paths，每行一条，已在应用层验证和规范化
-- ARGV[6] = new settings_id，仅在首次创建 hash 时使用
-- ARGV[7] = updated_at，ISO_LOCAL_DATE_TIME
-- ARGV[8] = updated_by

local actual = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
if actual ~= tonumber(ARGV[1]) then
    return { 0, actual }
end

local nextVersion = actual + 1
local settingsId = redis.call('HGET', KEYS[1], 'settings_id') or ARGV[6]
redis.call('HSET', KEYS[1],
    'enabled', ARGV[2],
    'authenticated_requests_per_minute', ARGV[3],
    'anonymous_requests_per_minute', ARGV[4],
    'excluded_paths', ARGV[5],
    'settings_id', settingsId,
    'version', nextVersion,
    'updated_at', ARGV[7],
    'updated_by', ARGV[8])
return { 1, nextVersion, settingsId }
```

设置 hash 不设置 TTL。生产环境必须开启 Redis 持久化，并把现有 `redis.clear-on-startup` 配为 `false`。如果 Redis 被有意清空或从不含该 hash 的备份恢复，服务安全地回退到部署默认 10/5；管理员可重新保存目标设置。这是“不新增数据库迁移”的明确取舍。

### 6.3 无缓存的动态刷新边界

每个待限速请求执行以下两步：

1. 通过 `HMGET rate-limit:settings:v1 enabled authenticated_requests_per_minute anonymous_requests_per_minute excluded_paths settings_id version updated_at updated_by` 得到一次完整、经验证的有效设置快照；hash 缺失时使用 YAML 默认值和内部 `settings_id = default`。
2. 快照 `enabled=false` 时继续过滤器链，不解析 IP、不执行路径匹配和单桶脚本，也不添加额度响应头；`enabled=true` 时再按路径规则和适用额度执行决策。

不使用 JVM 本地 TTL 缓存、定时刷新或 `@RefreshScope`。管理员成功更新 Redis hash 后，之后开始执行 `HMGET` 的请求会使用新版本；已经读取旧快照的在途请求可能按旧额度完成。这是唯一的并发边界，范围仅限已在执行的请求，同时避免在 Redis Cluster 中把设置 key 和所有桶 key 强制放进同一哈希槽。

为减少热路径开销，每个节点只用 `AtomicReference` 保留**一份**不可变的“`(settings_id, version, excluded_paths 原文) -> List<PathPattern>`”编译快照，不缓存 Redis 设置值或历史版本：每次请求仍读取 Redis 的代际、版本、路径原文与数值额度；只有三者任一变化时才解析 `excluded_paths` 并原子替换最多 20 个匹配器。`settings_id` 防止 Redis 清空后新设置从 version 1 重新开始时错误复用旧匹配器；路径原文比较则能检测绕过 CAS 的人工 Redis 修改。这样动态刷新语义不变，避免每次请求重复分割字符串和编译路径模式，也不会因管理端长期修改而让缓存无界增长。

### 6.4 额度调整后的桶行为

更新设置时不删除任何桶：

| 调整 | 结果 |
| --- | --- |
| 提高，例如 10 到 20 | 已接受记录保留，下一请求立即可继续接受，直到新额度用完。 |
| 降低，例如 10 到 5 | 已接受记录保留；已占用 10 次的主体会收到 `429`，直到第 6 条最早记录离开滚动窗口。脚本据此计算一次即可成功的 `Retry-After`，这样不能通过“保存更严格设置”重置历史并绕过规则。 |
| 删除 settings hash | 现有桶自然过期，后续请求使用 YAML 默认值。 |

### 6.5 开关调整后的行为

| 调整 | 结果 |
| --- | --- |
| `enabled=true -> false` | 后续受管请求立即放行，但仍执行 Spring Security 鉴权、CORS、CSRF、请求大小、幂等和业务级保护；不读取/写入限速桶，也不发送 `X-RateLimit-*`。已有桶不删除。 |
| `enabled=false -> true` | 后续请求恢复固定排除、动态路径匹配和滑动窗口判断；关闭期间没有新增记录，重新开启时仍使用关闭前尚未过期的桶历史。 |
| `filter-enabled=false` | 应用启动时不注册 `ApiRateLimitFilter`，属于部署/测试级静态开关；不能通过管理端动态开启。 |

### 6.6 动态路径规则调整后的行为

动态规则也随设置版本原子替换：

| 调整 | 结果 |
| --- | --- |
| 新增规则 | 后续命中路径立即跳过限速；已有用户/IP 桶不删除，规则移除后仍在一分钟内的历史记录继续生效。 |
| 删除规则 | 后续请求立即重新接受限速；先前免限速期间没有写入桶，因此不会追溯扣减额度。 |
| 同时改额度和规则 | 单个版本快照同时决定“是否跳过”和“使用哪一额度”，不会出现新规则配旧额度的混合判断。 |

动态规则仅作用于本次请求的路径，不会使同一用户在其他未命中路径上获得额外额度；用户桶仍按用户全局共享，而不是按接口拆分。

## 7. Spring Security 接入

创建 `ApiRateLimitFilter : OncePerRequestFilter`，在 `SecurityConfig` 中紧跟 JWT 过滤器注册：

```kotlin
.addFilterBefore(
    JwtAuthenticationFilter(jwtService, devTokenManager),
    UsernamePasswordAuthenticationFilter::class.java,
)
.addFilterAfter(apiRateLimitFilter, JwtAuthenticationFilter::class.java)
```

`shouldNotFilter` 先跳过 `/api/**`、`/admin/api/**` 以外的路径，再负责第 2.1 节的固定系统排除规则。过滤器在控制器派发和授权判定前短路拒绝：公开请求、非法令牌请求和受保护请求都进入同一个全局限速器，但有效 JWT 仍优先使用用户维度。

`ApiRateLimitFilter` 仅通过 `SecurityFilterChain.addFilterAfter` 注册：在 `SecurityConfig` 内使用已注入的 `ApiRateLimitService`、`ClientIpResolver` 和 `ObjectMapper` 直接构造过滤器实例，再传给 `addFilterAfter`。该过滤器不得标注为 Servlet `@Component`、不得声明为自动注册的 `Filter` Bean，也不得通过 `FilterRegistrationBean` 注册；否则同一个请求可能在两个过滤器链中被计算两次。`OncePerRequestFilter` 应只处理 `DispatcherType.REQUEST`，跳过 `ERROR`、`ASYNC` 二次派发，避免一次业务请求因框架派发重复消耗额度。

固定排除和动态路径排除的顺序必须如下：

1. `shouldNotFilter` 处理范围外路径，以及 `OPTIONS`、健康检查、错误派发与外部 webhook 等固定系统排除；外部 webhook 仅在第 2.1 节列出的 `POST` 方法上跳过，其他方法仍进入限速与后续安全链；这些固定排除不访问 Redis。
2. 其余受管路径读取一次有效设置快照，并使用该快照的已编译 `excluded_paths` 匹配当前路径。
3. 命中动态规则时直接继续过滤器链，不解析 IP、不执行桶脚本、不添加额度头；未命中时才进行身份解析与桶决策。

这样动态免限速路径同样能立即刷新，同时不会让规则命中与限速判断使用不同版本的设置。动态规则匹配异常、规则版本与缓存内容不一致、或 Redis 设置无法读取时，按第 8.3 节失败关闭，不能以“无法判断”为理由放行。

```text
HTTP 请求
  -> CORS
  -> JwtAuthenticationFilter
  -> ApiRateLimitFilter
       -> 范围外或固定排除：继续（不访问 Redis）
       -> 读取 Redis 实时设置
       -> 动态免限速路径：继续
       -> 有 Long principal：用户桶；否则：客户端 IP 桶
       -> 执行 sorted-set 原子决策
       -> 允许：写额度响应头，继续
       -> 拒绝：写 429 JSON，结束
  -> Spring Security 授权
  -> Controller / Service
```

建议划分为三个职责清晰的组件：

| 组件 | 职责 |
| --- | --- |
| `ApiRateLimitFilter` | 固定路径排除、动态规则匹配、HTTP 身份选择、响应头和 `429`/`503` JSON 写入。 |
| `ApiRateLimitService` | Redis 桶脚本调用，返回 `RateLimitDecision` 值对象；不依赖 Servlet API。 |
| `RateLimitSettingsService` | YAML 回退、Redis hash 解析、规则校验/规范化、按设置代际/版本/原文缓存单份 `PathPattern` 快照、CAS 更新。 |

## 8. HTTP 响应约定

### 8.1 已允许请求

每个经过限速器并被允许的请求响应中增加诊断头：

```text
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 9
```

数值对应本请求实际使用的身份类别，客户端只能将其用于展示，不能把它当作自身限速依据。

### 8.2 被拒绝请求

由于过滤器短路时不会进入 `GlobalExceptionHandler`，必须直接写出项目统一响应信封：

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json;charset=UTF-8
Retry-After: 37
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0

{
  "status": 429,
  "message": "请求过于频繁，请在 37 秒后重试",
  "data": {}
}
```

`Retry-After` 向上取整到秒，最小为 `1`。在 `SecurityConfig` 的 `exposedHeaders` 中保留已有的 `Retry-After`，并加入 `X-RateLimit-Limit` 与 `X-RateLimit-Remaining`，以便浏览器客户端读取。

### 8.3 Redis 故障

限速是反滥用边界。读取设置或执行桶脚本失败时必须失败关闭，返回：

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json;charset=UTF-8
Retry-After: 1

{
  "status": 503,
  "message": "系统繁忙，请稍后重试",
  "data": {}
}
```

不能降级为本地计数器或静默放行，否则多节点下“全局最多 10/5 次”的承诺不再成立。过滤器自行捕获 Redis 异常，因为已经短路的响应不会经过 `GlobalExceptionHandler`。

## 9. 管理端 API

新增 `controller/admin/AdminRateLimitController`。遵守项目控制器约定：

- 每个 HTTP 输入使用显式 `@RequestParam`，wire name 使用 snake_case；
- 每个端点在方法内部声明自己的 `data class Response`；
- 在端点内直接构造 service command、映射结果和 `val rs = Response(...)`；
- 返回 `ResponseEntity<shared.Response>`，通过注入的 `ResponseBuilder` 构建；
- 不创建请求 wrapper DTO、控制器级响应 DTO 或 mapper/helper。

三个接口都受现有 `/admin/api/**` 的 `ADMIN` 角色保护，并在服务层调用 `AdminAccessService.requireAdmin(adminId)`。

### 9.1 读取当前设置

```http
GET /admin/api/rate-limit-settings
Authorization: Bearer <access-token>
```

```json
{
  "status": 200,
  "message": "OK",
  "data": {
    "enabled": true,
    "window_seconds": 60,
    "authenticated_requests_per_minute": 10,
    "anonymous_requests_per_minute": 5,
    "excluded_paths": [
      "/api/catalog/**",
      "/api/files/public"
    ],
    "version": 3,
    "source": "REDIS",
    "updated_at": "2026-08-07T11:30:00",
    "updated_by": 42
  }
}
```

尚未保存运行时设置时，`version` 为 `0`，`source` 为 `DEFAULT`，`updated_at` 与 `updated_by` 为 `null`，`excluded_paths` 返回 YAML 的 `default-excluded-paths`。日期时间使用 `LocalDateTime` 和 `ISO_LOCAL_DATE_TIME`，不使用 epoch 或自定义格式。

### 9.2 更新设置

```http
PUT /admin/api/rate-limit-settings?authenticated_requests_per_minute=10&anonymous_requests_per_minute=5&excluded_path=/api/catalog/**&excluded_path=/api/files/public&expected_version=3
Authorization: Bearer <access-token>
```

端点参数形状如下：

```kotlin
@RequestParam("authenticated_requests_per_minute") @Min(1) @Max(1000) authenticatedRequestsPerMinute: Int,
@RequestParam("anonymous_requests_per_minute") @Min(1) @Max(1000) anonymousRequestsPerMinute: Int,
@RequestParam("excluded_path", required = false) excludedPaths: List<String>?,
@RequestParam("enabled") enabled: Boolean,
@RequestParam("expected_version") @Min(0) expectedVersion: Long,
```

控制器类加 `@Validated`。端点直接构造更新 command；`excludedPaths ?: emptyList()` 表示完整替换的目标列表。若请求显式给出空值（例如 `excluded_path=`），服务端返回 `400`，不把它解释为清空；清空只能通过**不传该参数**表达。服务端在写入前按第 2.3 节规范化、去重、排序和校验。版本不匹配时返回项目统一格式的 `409 Conflict`，提示管理员重新加载当前设置；这样两个打开的管理页面不会静默覆盖对方的修改。

`PUT` 查询参数与现有 `AdminPanelUI` 的 `useHttp` 调用约定一致，同时满足“不引入请求 wrapper DTO”的控制器规则。

### 9.3 仅更新开关

```http
PUT /admin/api/rate-limit-settings/enabled?enabled=false&expected_version=3
Authorization: Bearer <access-token>
```

该端点也接受 `PATCH` 方法。服务端用同一个 Redis CAS 版本更新脚本保存开关，并返回完整的最新设置快照；额度和免限速路径保持不变。版本不匹配仍返回 `409 Conflict`。关闭后不会删除桶，重新开启时仍按尚未过期的历史记录判断。

## 10. 管理面板设计

新增限速设置入口及以下前端文件：

| 文件 | 职责 |
| --- | --- |
| `AdminPanelUI/app/types/rate-limit.ts` | 声明含 `enabled` 与 `excluded_paths: string[]` 的 snake_case `RateLimitSettings` 类型。 |
| `AdminPanelUI/app/composables/useRateLimitApi.ts` | 对 `/admin/api/rate-limit-settings` 提供 `getSettings()`、`updateSettings()` 和 `updateEnabled()`。 |
| `AdminPanelUI/app/pages/rate-limits/index.vue` | 加载、编辑开关、额度和路径列表、保存、刷新、版本冲突和 API 错误状态。 |
| `AdminPanelUI/app/layouts/default.vue` | 新增“限速设置”菜单项及路由激活规则。 |

页面提供一个全局 `NSwitch` 开关和两个限制在 `1..1000` 的数字输入：

- 是否启用全局 API 限速；

- 已登录用户每分钟请求次数；
- 未登录用户每分钟请求次数。

页面还提供“免限速路径”可添加/删除列表控件，每项使用文本输入并在前端做与第 2.3 节一致的即时格式提示；服务端校验始终是最终约束。不得用自由文本多行框传递逗号或换行分隔内容，避免 URL 编码、空项和规则含义不清。保存时将每一项作为一个同名 `excluded_path` 查询参数传递；列表为空时不传该参数，明确表示清空动态规则。

固定的 60 秒滑动窗口、当前配置来源、版本、最后修改时间和修改管理员以只读信息展示。页面加载时读取设置；开关变更立即调用 `updateEnabled()` 并携带当前 `expected_version`，额度和路径保存则携带当前开关、两个数值、路径列表与 `expected_version`。两种操作成功后都直接用响应快照刷新本地状态。收到 `409` 时丢弃旧编辑、重新加载服务器快照并提示冲突。手动刷新仅用于查看其他管理员的修改，不承担配置传播职责。

服务端每次请求读取 Redis 设置，因此一次成功保存会对所有节点生效，不需要刷新页面、轮询或重启。

## 11. 安全、可靠性与可观测性

### 11.1 安全要求

- 只有直连对端属于受信任代理时才解析转发地址头。
- 匿名桶使用独立 HMAC 密钥；日志中不记录原始 IP、完整桶 key 或 JWT。
- 桶成员仅由服务端 UUID 生成，用户输入不得直接拼接 Redis key。
- 设置修改仅允许管理员操作，控制器和服务层都做范围校验，并使用乐观锁。
- 不提供客户端可控的绕过头；动态免限速仅能通过第 2.3 节受限的管理员路径规则配置。
- `enabled=false` 只关闭本全局限速器，不关闭鉴权、CORS、CSRF、请求大小、幂等或业务级限流；`filter-enabled=false` 仅用于部署/测试时静态卸载过滤器。
- 免限速规则不改变已有 Spring Security 鉴权、CSRF/CORS、请求大小、幂等或业务级限流；路径可跳过的是本全局 API 限速器，而非其他安全边界。

### 11.2 指标与日志

记录不含用户 ID、IP、原始 URI 等高基数标签的 Micrometer 指标：

```text
shopmall.rate_limit.requests{identity=authenticated,outcome=allowed}
shopmall.rate_limit.requests{identity=authenticated,outcome=rejected}
shopmall.rate_limit.requests{identity=anonymous,outcome=allowed}
shopmall.rate_limit.requests{identity=anonymous,outcome=rejected}
shopmall.rate_limit.errors
shopmall.rate_limit.settings_updates
shopmall.rate_limit.exclusions{source=fixed}
shopmall.rate_limit.exclusions{source=dynamic}
```

每次配置更新仅记录一次管理员 ID、调整前后数值、版本和 `LocalDateTime`。不为每个 `429` 单独写应用日志，避免被限速攻击反过来放大日志量。现有系统状态页面已经提供 Redis 可用性、延迟和 key 数量，可作为首要运行指标。

### 11.3 运行约束

- 所有应用节点必须使用同一 Redis 逻辑库或集群命名空间，Redis 才能成为全局决策点。
- 生产必须设置 `redis.clear-on-startup=false`；项目当前本地默认 `true` 会清掉已保存的运行时策略。
- Redis 持久化和备份应覆盖所用数据库。丢失运行时设置不会放开超过 YAML 默认的规则，但自定义值需要重新保存。
- Redis 限速错误应触发告警；请求返回 `503`，而不是静默越过限速。
- 限速桶是可再生缓存数据。承载该逻辑库的 Redis **实例**应配置 `maxmemory-policy noeviction`；若不能调整共享实例，应使用专用 Redis 实例并为内存、延迟和驱逐事件告警。仅更换逻辑数据库编号不能防止实例级内存驱逐；一旦 Redis 因内存压力驱逐桶，主体会在窗口内提前获得额度，不能把该情形误认为严格限速仍成立。

## 12. 测试计划

Lua 并发正确性必须通过真实 Redis 或现有 Testcontainers 模式验证，不能只用 mock 调用序列替代原子性测试。

| 测试 | 预期 |
| --- | --- |
| 登录用户额度 | 同一用户前 10 次允许，第 11 次为 `429`。 |
| 匿名额度 | 同一解析 IP 前 5 次允许，第 6 次为 `429`。 |
| 身份隔离 | 不同用户互不共享额度；不同规范化 IP 互不共享匿名额度。 |
| 滑动边界 | 恰好 60 秒前的记录被清理；整分钟边界不能突发绕过。 |
| 并发 | 同一身份大量并发下，允许数精确等于 10 或 5，绝不超过。 |
| 拒绝行为 | 连续 `429` 不延长 `Retry-After`，也不延长闲置桶的 TTL。 |
| JWT 分类 | 有效 access JWT 使用用户桶；无、过期、非法 JWT 使用 IP 桶。 |
| 代理安全 | 直连客户端伪造 `X-Forwarded-For` 无效；可信代理链能解析真实 IP。 |
| 固定排除 | `OPTIONS`、健康检查、指定 `POST` 外部 webhook 不计数；相同 webhook 路径的其他方法、普通管理端和限速设置接口会计数。 |
| 动态路径排除 | 精确路径与子树路径都不读写桶；路径列表替换、清空、非法模式和规则命中顺序符合第 2.3 节。 |
| 路径安全 | `/api/**`、认证、管理端、webhook 等禁用模式不能保存；匹配不受 query string 或伪造编码路径绕过。 |
| Redis 重置 | Redis 清空后默认路径规则生效；新 `settings_id` 不会复用旧节点的路径匹配缓存。 |
| 默认规则覆盖 | Redis 首次保存的空路径列表会覆盖 YAML 默认路径列表；删除 Redis settings hash 后恢复 YAML 默认列表。 |
| 过滤器派发 | 同一 `REQUEST` 只计数一次；`ERROR`、`ASYNC` 二次派发和重复 Servlet 注册不产生额外桶写入。 |
| HTTP 合约 | `429` 含统一 JSON、正确的 `Retry-After`、额度响应头及 CORS 暴露。 |
| 动态增减 | 下一次设置读取立即使用新额度；降低额度不清空桶历史且 `Retry-After` 对应首次可能成功的时刻。 |
| 设置冲突 | 相同版本的两个更新中仅一个成功，另一个返回 `409`。 |
| Redis 故障 | 读取配置或执行脚本失败时统一返回 `503` 与 `Retry-After: 1`。 |
| 运行时开关 | `enabled=false` 时不访问路径匹配器和桶服务、不添加额度头；`enabled=true` 恢复限速；开关更新使用版本 CAS。 |

## 13. 实施清单

1. 新增 `RateLimitProperties`、`RateLimitConfig`、可信 `ClientIpResolver`、设置存储、桶服务、路径规则校验器和 `ApiRateLimitFilter`。
2. 使用 `StringRedisTemplate` 实现单桶 sorted-set 决策 Lua 脚本及版本化设置更新 Lua 脚本；额度下调时返回首次可能成功的 `Retry-After`。
3. 将过滤器注册在 `JwtAuthenticationFilter` 后，实现固定排除、动态路径排除、版本化 `PathPattern` 缓存，以及直接写出的统一 `429`/`503` 响应。
4. 增加 YAML 回退默认值、运行时 `enabled` 与部署级 `filter-enabled`、独立 IP-HMAC 密钥、默认动态路径列表、额度 CORS 暴露头和生产 Redis 持久化配置。
5. 新增 `AdminRateLimitController`，按控制器规范实现内嵌响应类、snake_case 查询参数、完整替换的 `excluded_path` 列表及专用开关接口。
6. 新增管理设置页面、composable、类型、菜单入口、开关、路径列表编辑、输入验证与版本冲突处理。
7. 补齐第 12 节中的 Redis 集成、过滤器、开关、控制器、路径匹配和前端交互测试。

本设计不需要新增数据库实体、schema 变更或数据库迁移。
