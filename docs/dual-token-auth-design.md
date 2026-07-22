# 双 Token 认证设计：Access + Refresh（原子轮换 · 复用检测 · Spring Security）

> 背景：本项目当前为**单 JWT** 模型（3 天有效期 + 每请求查 Redis 白名单 `LoginToken`）。本文设计一套 **Access / Refresh 双 Token** 方案，缩短泄漏窗口、去掉每请求 Redis 查询，并通过**刷新令牌原子轮换 + 复用检测**在令牌被盗时主动失效。
>
> 技术栈约束：Spring Boot 4.1.0 + Kotlin 2.3.21 + Spring Security 6.x + Redis（Lettuce）。不引入 OAuth2 Authorization Server、不引入 Redisson；沿用现有 `JwtService`（纯 HS256）、`ResponseBuilder`、`@AuthenticationPrincipal userId: Long`。
>
> 设计目标对齐 `docs/order-system-design.md` 的 lean 风格：最小依赖、复用既有模式、可审计。
>
> **存储选型**：刷新令牌的轮换状态用 **`StringRedisTemplate` + Lua** 管理（非 `@RedisHash`）——`config/RedisTemplateConfig.kt:9-13` 明示默认 `RedisTemplate` 把 key 也 JSON 序列化，Lua 直接操作这类 key 极脆弱；项目注释本身就建议「需要可读 key 的场景改用 StringRedisTemplate」。这也让轮换的「ACTIVE→USED」可用单条 Lua 原子完成（见 §3、§4.2）。

---

## 一、现状与要解决的问题

### 1.1 现有单 Token 模型（`authentication/JwtService.kt` + `entity/redis/LoginToken.kt`）

- 登录签发一张 HS256 JWT，`sub`=userId、随机 `jti`、`ttl-seconds=259200`（3 天）。
- `JwtAuthenticationFilter` 每个受保护请求：① `JwtService.verify`（签名+过期）→ ② `LoginTokenAuthentication.isValid`（Redis 白名单 `userId + jti + UA` 三匹配）。
- 撤销靠 Redis 删除（登出 / 改密 `revokeAll(userId)`）。
- `DevTokenManager` 提供本地固定令牌旁路（`enabled=false` 默认关闭）。

### 1.2 三个缺口

| 缺口 | 说明 |
|---|---|
| **泄漏窗口过大** | 访问令牌有效期 3 天。一旦通过 XSS / 日志 / 代理泄漏，在 Redis 白名单被删前一直可用。长有效期 bearer 是最大的单点风险。 |
| **每请求一次 Redis 查询** | 白名单校验对**每个**受保护接口都打一次 Redis（`loginTokenRepository.findById(jti)`）。读写密集场景这是无谓的延迟与连接开销——令牌完整性已由 HS256 保证。 |
| **被盗不可感知** | 现模型只能在**事后**手动登出/改密撤销。令牌一旦被复制使用，合法用户与攻击者各持一份有效令牌，系统无法区分、无法主动失效。 |

> `SecurityConfig.kt:49-50` 的注释已写明「刷新令牌走 HttpOnly Cookie」，`/api/auth/refresh` 也已在 `permitAll` 列表中——本设计正好把这些预留缺口补齐。

### 1.3 设计目标

- **短窗口**：访问令牌 ≤ 30min（可配），泄漏影响面与时限同时收敛。
- **零 Redis（常态）**：受保护接口仅验签名+过期+类型，不再每请求查 Redis。
- **可撤销**：刷新令牌落 Redis，登出/改密可立即阻断**续期**。
- **原子轮换**：`ACTIVE→USED` 由单条 Lua 原子完成，并发刷新不会双双命中 ACTIVE、不会分叉会话族。
- **重试不误判**：已 USED 的 refresh 在短 **grace 窗口**内再现视为合法重试（响应丢失重发），窗口外或跨 UA 才判被盗 → 撤销整族。
- **被盗可感知**：复用检测命中即 `revokeFamily`，剥夺攻击者的持续访问能力（其 access 最长活一个 access TTL）。
- **类型强隔离**：访问令牌不得用于刷新、刷新令牌不得用于访问，由 `typ` claim + 双端校验强制。
- **Spring Security 原生集成**：令牌翻译为 `Authentication`，角色映射为 `GrantedAuthority`，鉴权交回 `authorizeHttpRequests`。

---

## 二、整体架构

```
登录  POST /api/auth/login   (identifier, password)
  │   凭据校验（AuthServiceImpl，沿用）
  │   签发：access(typ=access, 30min) + refresh(typ=refresh, 7d, fam=<新UUID>)
  │   写 Redis：refresh:token:<jti>(HASH, status=ACTIVE) + user/fam 索引 SET
  ▼
响应：body = { access_token, token_type:"Bearer", expires_in, user_info }
      Set-Cookie: refresh_token=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api/auth

访问受保护接口  GET/POST /api/**   (Header: Authorization: Bearer <access>)
  │   JwtAuthenticationFilter：仅接受 typ=access；验签名+过期（无状态，不查 Redis）
  │   → SecurityContext.authentication = UsernamePasswordAuthenticationToken(userId, authorities=[ROLE_*])
  ▼
authorizeHttpRequests：authenticated() / hasRole('ADMIN') 判定（/api/admin/project/** 仍 permitAll 走 controlPassword）

续期  POST /api/auth/refresh   (Cookie: refresh_token=<jwt>)   ← permitAll，无 Bearer
  │   ① verify(refresh)：签名+过期+typ=refresh 失败 → 401
  │   ② 预生成 newRefreshJti，跑 Lua(refresh:token:<jti>)，原子判定：
  │        无记录        → unknown → 401
  │        ACTIVE        → 原子置 USED + 记 replacedBy/rotatedAt → rotate（继续签发）
  │        USED 且在 grace 内 → grace：沿 replacedBy 找到当前 ACTIVE 尖端，从尖端再轮换（同族），UA 必须一致
  │        USED 且超 grace / UA 不一致 → reuse：被盗 → revokeFamily(fam) → 401
  │   ③ 一致性（userId/UA）校验，user.enabled 校验
  │   ④ 签发新 access + 新 refresh（用预生成 jti），写新 HASH(ACTIVE) + 索引
  ▼
响应：body = { access_token, expires_in }   Set-Cookie: refresh_token=<新jwt>   （refresh 滚动）

登出  POST /api/auth/logout   (Cookie: refresh_token?)   ← permitAll（access 过期也要能登出）
  │   有 cookie：删 Redis 当前 refresh 记录 + 索引（可选 logoutAll：删该用户全部）
  │   Set-Cookie: refresh_token=; Max-Age=0
  ▼
access 不可服务端撤销 → 自然过期（≤30min）；refresh 已删 → 无法续期
```

### 无错与安全保证链

| 风险 | 防护机制 |
|---|---|
| 访问令牌泄漏 | 有效期 ≤30min（`JWT_ACCESS_TTL_SECONDS`），缩小窗口 |
| 刷新令牌泄漏 | HttpOnly Cookie（JS 不可读）+ 仅 `Path=/api/auth` + Secure + SameSite |
| 并发刷新双双命中 ACTIVE | **单条 Lua 原子 ACTIVE→USED**：恰好一方 rotate，另一方进 grace（§4.2） |
| 刷新响应丢失、客户端重试 | USED 在 **grace 窗口**内再现视为合法重试，沿 replacedBy 续换，不撤销（§4.2） |
| 刷新令牌被盗用 | 超 grace 或跨 UA 的 USED 再现 → `revokeFamily` 撤销整族（§4.2/§4.3） |
| 用 refresh 当 access（或反之） | `typ` claim + 过滤器/刷新端点**双向**校验类型（§4.1） |
| 跨设备/UA 重放 refresh | HASH 记录绑定签发 UA，刷新与 grace 路径都校验一致（§4.2） |
| USED 记录 TTL 被重置堆积 | 轮换只 `HSET` 字段、**不重写整实体**，TTL 不变；到期自动清理（§3.2） |
| 改密后会话仍存活 | `revokeAll(userId)` 删该用户**全部 refresh**；access 在 ≤30min 内自然过期（§5.4，行为变化见 §十） |
| CSRF（cookie 凭证） | SameSite + refresh 响应只回 access token（跨域不可读），CSRF 收益近零（§6.3） |

---

## 三、数据模型与存储

### 3.1 JWT Claims — `JwtService` 扩展

两张令牌共享同一 HS256 密钥（`shopmall.security.jwt.secret`），以 **`typ` claim** 区分：

| claim | access | refresh | 说明 |
|---|---|---|---|
| `sub` | userId | userId | 主体 |
| `jti` | 随机 UUID | 随机 UUID | 唯一标识；refresh 的 jti 即 Redis 主键 |
| `typ` | `"access"` | `"refresh"` | **类型强隔离的钥匙**，必填、必校验 |
| `fam` | — | family UUID | 会话族标识，复用检测时按族撤销；登录时生成，同族刷新不换 |
| `role` | `CUSTOMER`/`ADMIN` | — | 角色，映射为 Spring Security authority（§5.2） |
| `iat` / `exp` | 秒级 NumericDate（UTC） | 同左 | 与现有约定一致（遵循项目时间约定，`LocalDateTime`+ISO-8601） |

`JwtService` 保持「纯逻辑、无 Spring/Redis」定位不变，仅扩展 claims：

```kotlin
enum class TokenType { ACCESS, REFRESH }

data class Claims(
    val userId: Long,
    val jti: String,
    val type: TokenType,
    val familyId: String?,     // 仅 refresh 有
    val role: String?,         // 仅 access 有
    val issuedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
)

/** 签发；type 决定写入哪些 claim。jti 可显式传入（轮换时预生成，供 Lua 原子记 replacedBy）。 */
fun issue(
    userId: Long,
    type: TokenType,
    ttlSeconds: Long,
    familyId: String? = null,
    role: String? = null,
    jti: String = UUID.randomUUID().toString(),
): IssuedToken

/** 验签+过期+（可选）类型校验；任一失败返回 null。role 非法亦返回 null（见下）。 */
fun verify(token: String, expectedType: TokenType? = null): Claims?
```

payload 增字段对应的正则（沿用现有 `parseClaims` 的手写 JSON 抽取，避免引 Jackson）：

```kotlin
val TYP_REGEX = Regex(""""typ"\s*:\s*"(access|refresh)"""")
val FAM_REGEX = Regex(""""fam"\s*:\s*"([0-9a-fA-F-]{36})"""")
// role 放宽为大写+下划线，避免新增角色时正则失配导致静默降级（见下）
val ROLE_REGEX = Regex(""""role"\s*:\s*"([A-Z_]+)"""")
```

> **role 校验策略（防静默越权降级）**：`parseClaims` 解析出 `role` 后，必须与已知角色集（`User.Role.name`）比对——**未知角色直接令 `verify` 返回 null**（验签失败），而不是回退成 `CUSTOMER`。否则将来加 `MANAGER` 等角色时，旧 `JwtService` 部署会把带新角色的 access 解析为 null-role → 过滤器降级为 CUSTOMER，造成静默越权降级。`ROLE_REGEX` 放宽只解决「正则不匹配」一层；真正的约束放在 `parseClaims` 的白名单校验里。

> `verify` 内部：解析后若 `expectedType != null && claims.type != expectedType` → 返回 null（类型不符等同验签失败）。**这是防止 refresh 当 access 使用的最后防线**，过滤器与刷新端点都应显式传 `expectedType`。

### 3.2 `RefreshToken` 存储 — `StringRedisTemplate` + Lua（取代 `@RedisHash`）

**为何不用 `@RedisHash`**：① `config/RedisTemplateConfig.kt:9-13` 明示默认 `RedisTemplate` 把 key 也 JSON 序列化（带 unsafe 类型），Lua 直接按 `refresh_token:<jti>` 寻址会对不上序列化后的带引号/带类型 key，极脆弱；② 项目注释本身就建议「需要可读 key 的场景改用 StringRedisTemplate」；③ 轮换要做**原子 CAS**（`ACTIVE→USED`），Lua 是最稳的实现，而 Lua 需要可读 key。故刷新令牌改由 `StringRedisTemplate` 管理的 Redis 结构承载。

**Redis key 布局**（全部 plain string key，自注入 `StringRedisTemplate`）：

| key | 类型 | 字段/成员 | TTL |
|---|---|---|---|
| `refresh:token:<jti>` | HASH | `userId`、`familyId`、`userAgent`、`status`(`ACTIVE`/`USED`)、`replacedBy`(USED 时记后继 jti)、`rotatedAt`(USED 时记 epoch 秒) | = 该 refresh 剩余有效期 |
| `refresh:idx:user:<userId>` | SET | 该用户全部活跃/已轮换 jti | = refresh TTL（每次 `SADD` 续期） |
| `refresh:idx:fam:<familyId>` | SET | 该族全部 jti | = refresh TTL（每次 `SADD` 续期） |

**写入/清理不变量**（由 `RefreshTokenStore` 统一封装，调用方不直接碰 Redis）：

- **签发（登录或轮换出新）**：`HMSET refresh:token:<jti> ... status=ACTIVE` + `EXPIRE <ttl>` + `SADD` 两个索引 + 各 `EXPIRE <ttl>`。ttl = `ChronoUnit.SECONDS.between(nowUtc, refreshExp).coerceAtLeast(1)`。
- **轮换标 USED**：**只 `HSET status=USED replacedBy=<newJti> rotatedAt=<now>`，不重写整 HASH、不重设 EXPIRE** → TTL 原样保留，避免 USED 记录被重置成满 TTL 堆积（这是改用 raw `HSET` 相对 `@RedisHash save()` 的额外收益）。
- **`revokeOne(jti, userId, familyId)`**：`DEL refresh:token:<jti>` + `SREM` 两个索引 SET。登出语义只删当前记录，**不动同族其他记录**（区别于 `revokeFamily` 的整族撤销）。
- **`revokeFamily(famId)`**：`SMEMBERS refresh:idx:fam:<famId>` → 逐个「**先读 HASH 的 `userId` 再 `DEL refresh:token:<jti>`**」+ `SREM refresh:idx:user:<userId>` 对侧索引 → `DEL refresh:idx:fam:<famId>`。
- **`revokeAll(userId)`**：`SMEMBERS refresh:idx:user:<userId>` → 逐个「**先读 HASH 的 `familyId` 再 `DEL refresh:token:<jti>`**」+ `SREM refresh:idx:fam:<familyId>` 对侧索引 → `DEL refresh:idx:user:<userId>`。
- **交叉清理的读-删顺序**：`revokeFamily`/`revokeAll` 必须「先读 HASH 字段、再 `DEL` token key」——`DEL` 之后 HASH 字段已清空，后读会得 `null`，导致对侧索引的该 jti 不被 `SREM`，陈旧成员最长残留一个 refresh TTL。索引容忍陈旧成员兜底，但交叉清理收敛窗口。
- **索引容忍陈旧成员**：SET 可能引用已过期的 jti（成员 TTL 与 SET TTL 不完全对齐）；任何按 jti 的读取都以 HASH 是否存在为准，缺失即视为已失效。

> `RefreshTokenStore` 公共面（示意）：
> ```kotlin
> @Component
> class RefreshTokenStore(
>     private val redis: StringRedisTemplate,
>     private val props: JwtProperties,
> ) {
>     fun issueActive(jti, userId, familyId, ua, ttlSeconds): Issued   // HMSET+SADD+EXPIRE
>     fun decideRotation(jti, newJti, nowEpoch): RotationVerdict       // 跑 Lua（§3.3）
>     fun loadHash(jti): Map<String,String>?                           // 读 HASH（grace 找尖端/一致性校验用）
>     fun revokeOne(jti, userId, familyId): Boolean                    // 登出：删当前记录 + SREM 两索引（§4.3）
>     fun revokeFamily(familyId): Int
>     fun revokeAll(userId): Int
> }
> sealed interface RotationVerdict {
>     data object Unknown : RotationVerdict
>     data class Rotate(val remainingTtlMillis: Long) : RotationVerdict
>     data class Grace(val replacedBy: String) : RotationVerdict
>     data object Reuse : RotationVerdict
> }
> ```

### 3.3 原子轮换 Lua 脚本（核心）

`META-INF/scripts/refresh_rotate.lua`（启动时 `DefaultRedisScript` 加载，`resultType = List`）：

```lua
-- KEYS[1] = refresh:token:<jti>
-- ARGV[1] = now (epoch seconds)
-- ARGV[2] = grace seconds
-- ARGV[3] = newRefreshJti (预生成，原子写入 replacedBy)
-- return: {verdict[, payload]}
local key = KEYS[1]
local status = redis.call('HGET', key, 'status')
if not status then return {'unknown'} end

if status == 'ACTIVE' then
  -- 原子翻成 USED 并记后继；只改字段、不动 TTL
  redis.call('HSET', key, 'status', 'USED', 'replacedBy', ARGV[3], 'rotatedAt', ARGV[1])
  local pttl = redis.call('PTTL', key)
  return {'rotate', tostring(pttl)}
end

if status == 'USED' then
  local rotatedAt = tonumber(redis.call('HGET', key, 'rotatedAt')) or 0
  local age = tonumber(ARGV[1]) - rotatedAt
  if age >= 0 and age < tonumber(ARGV[2]) then
    local rep = redis.call('HGET', key, 'replacedBy')
    return {'grace', rep or ''}          -- 合法重试：沿 replacedBy 续换
  end
  return {'reuse'}                       -- 超窗口 / 被盗
end
return {'unknown'}
```

要点：

- **原子性**：Redis 单线程，`HGET status` + 条件 `HSET` 在一条 Lua 内完成。两个并发请求拿同一张 ACTIVE，**恰好一个**拿到 `rotate`（它把 status 写成 USED），另一个随后必然读到 USED → 进 `grace`/`reuse`。彻底消除「双双命中 ACTIVE → 分叉会话族」的竞态。
- **`grace` 分支沿用同一原语**：拿到 `grace(replacedBy)` 的请求，Kotlin 侧沿 `replacedBy`（必要时多跳）找到当前 `ACTIVE` 尖端，对尖端**再跑一次本脚本**完成轮换。这把「并发同 token」与「响应丢失重试」统一成同一条原子路径——都不会误撤销。
- **UA 门控在 Kotlin 侧**：`grace` 路径加载尖端 HASH 后必须校验 `userAgent == 请求 UA`，不一致则升格为 `reuse`（跨 UA 的 USED 再现即视为被盗）。UA 可伪造，属弱绑定、可配关。
- **TTL 不重置**：脚本只 `HSET` 字段，不发 `EXPIRE`，USED 记录按原始 exp 自然过期（修 §3.2「USED 堆积」隐患）。

---

## 四、关键流程实现细节

### 4.1 受保护请求：无状态 Access 验证（`JwtAuthenticationFilter` 改造）

去掉每请求 Redis 查询，**仅** `JwtService.verify(token, expectedType = ACCESS)`：

```kotlin
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val devTokenManager: DevTokenManager,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request, HttpServletRequest, response, filterChain) {
        val token = extractBearerToken(request) ?: run { filterChain.doFilter(request, response); return }
        if (SecurityContextHolder.getContext().authentication != null) {
            filterChain.doFilter(request, response); return
        }
        val claims = jwtService.verify(token, TokenType.ACCESS)   // 类型不符/role 非法 → null
        devTokenManager.fixedTokenUserId(claims)?.let { authenticate(it, "ROLE_ADMIN") }
            ?: claims?.let { authenticate(it.userId, "ROLE_${it.role ?: "CUSTOMER"}") }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(userId: Long, vararg roles: String) {
        val authorities = roles.map { SimpleGrantedAuthority(it) }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, authorities)
    }
    // extractBearerToken / BEARER_PREFIX 沿用
}
```

要点：

- **构造参数去掉 `loginTokenAuthentication`**：访问令牌不再查 Redis 白名单，过滤器无 Redis 依赖。`LoginTokenAuthentication.isValid` 的访问白名单职责随之退役。
- **`expectedType = ACCESS`**：刷新令牌（`typ=refresh`）当 Bearer 使用直接被拒（`verify` 返回 null），是类型隔离的执行点。
- **角色来自 claim**：access 内嵌 `role`（`verify` 已保证非已知角色则失败），映射 `ROLE_ADMIN`/`ROLE_CUSTOMER`，使 `authorizeHttpRequests` 可用 `hasRole(...)`（§5.2）。role 在签发时冻结，刷新时按 DB 当前角色重铸——管理者降级最长滞后一个 access TTL（≤30min），可接受。
- **DevTokenManager 旁路**：固定令牌仍是 access 语义，旁路判定优先于正常分支（细节见 §八，DevTokenManager 需随 `issue` 签名变化同步改，**并非不动**）。

### 4.2 刷新：原子轮换 + grace 重试 + 复用检测（`/api/auth/refresh`）

刷新逻辑并入既有 `LoginTokenAuthenticationImpl`（方案 A，§八）；以下为示意，实现见 `LoginTokenAuthenticationImpl`：

```kotlin
fun refresh(refreshJwt: String, requestUA: String): RefreshResult {
    // ① 签名 + 过期 + 类型必须是 refresh（refresh 不带 role claim，故 access 的 role 白名单校验在此不适用）
    val claims = jwtService.verify(refreshJwt, TokenType.REFRESH) ?: throw TokenInvalidException()
    val familyId = claims.familyId ?: throw TokenInvalidException()

    val user = userRepository.findById(claims.userId).orElse(null) ?: throw TokenInvalidException()
    if (!user.enabled) throw UserDisabledException()

    // ② 预生成 newJti，让 Lua 能原子写入 replacedBy
    val newJti = UUID.randomUUID().toString()
    return when (val v = store.decideRotation(claims.jti, newJti, nowEpoch())) {
        is RotationVerdict.Unknown  -> throw TokenInvalidException()
        is RotationVerdict.Reuse    -> {
            if (props.refresh.reuseDetect) {
                store.revokeFamily(familyId)
                log.warn("Refresh token reuse detected: family=$familyId user=${claims.userId}")
            }
            throw TokenInvalidException()           // 对外统一 401，不泄露原因
        }
        is RotationVerdict.Grace    -> rotateFromTip(claims, user, requestUA, familyId, v.replacedBy)  // 沿尖端续换
        is RotationVerdict.Rotate   -> doRotate(user, familyId, requestUA, claims.jti, newJti)
    }
}

/** grace：沿 replacedBy 找到当前 ACTIVE 尖端（容忍多跳），从尖端轮换；UA 必须一致，否则升格 reuse。 */
private fun rotateFromTip(
    claims: JwtService.Claims,
    user: User,
    requestUA: String,
    familyId: String,
    startReplacedBy: String,
): RefreshResult {
    var jti = startReplacedBy
    repeat(MAX_CHAIN_HOPS) {
        val hash = store.loadHash(jti) ?: return reuseAndRevoke(claims, familyId)   // 尖端已过期/被撤销
        if (hash["status"] != "ACTIVE") {
            val next = hash["replacedBy"]?.takeIf { it.isNotBlank() } ?: return reuseAndRevoke(claims, familyId)
            jti = next; return@repeat
        }
        if (hash["userAgent"] != requestUA) return reuseAndRevoke(claims, familyId)   // 跨 UA → 被盗
        if (hash["userId"]?.toLongOrNull() != claims.userId) return reuseAndRevoke(claims, familyId)
        // 在尖端上原子轮换（尖端 ACTIVE → USED，记 replacedBy=新预生成 jti）
        val freshJti = UUID.randomUUID().toString()
        when (val v = store.decideRotation(jti, freshJti, nowEpoch())) {
            // 本请求成功把尖端标 USED → 签发新令牌
            is RotationVerdict.Rotate -> return doRotate(user, familyId, requestUA, jti, freshJti)
            // 尖端已被并发请求先标 USED（合法重试的二次出现）：沿新 replacedBy 继续下钻，
            // 丢弃本次预生成的 freshJti，不撤销整族
            is RotationVerdict.Grace -> {
                val next = v.replacedBy.takeIf { it.isNotBlank() } ?: return reuseAndRevoke(claims, familyId)
                jti = next; return@repeat
            }
            // 超 grace 窗口的二次出现 / 尖端已失效 → 被盗，撤销整族
            else -> return reuseAndRevoke(claims, familyId)
        }
    }
    return reuseAndRevoke(claims, familyId)                       // 超最大跳数仍未找到可用尖端 → 撤销整族
}

/** 复用检测命中：撤销整族并对外回 401。 */
private fun reuseAndRevoke(claims: JwtService.Claims, familyId: String): RefreshResult {
    if (props.refresh.reuseDetect) {
        store.revokeFamily(familyId)
        log.warn("Refresh token reuse detected: family=$familyId user=${claims.userId}")
    }
    throw TokenInvalidException()
}

/** 真正签发：Lua 已把 oldJti 原子标 USED；这里签新 access+refresh 并落新 ACTIVE 记录。 */
private fun doRotate(user: User, familyId: String, requestUA: String, oldJti: String, newJti: String): RefreshResult {
    val access = jwtService.issue(user.id!!, TokenType.ACCESS, props.access.ttlSeconds, role = user.role.name)
    val refreshIssued = jwtService.issue(user.id!!, TokenType.REFRESH, props.refresh.ttlSeconds, familyId = familyId, jti = newJti)
    store.issueActive(newJti, user.id, familyId, requestUA, ttlSeconds(refreshIssued.expiresAt))
    log.debug("Rotated refresh family=$familyId old=$oldJti new=$newJti")
    return RefreshResult(accessToken = access.token, accessExpiresIn = props.access.ttlSeconds, refreshJwt = refreshIssued.token)
}
```

设计要点：

- **原子性在 Lua 一处收口**：`decideRotation` 是唯一改 `status` 的入口，单条 Lua 保证「同 jti 同时只有一方 Rotate」。并发同 token 的第二方拿到 `Grace`，沿 `replacedBy` 续换——**不会**双双签发独立 lineage，也**不会**误判 reuse 撤销整族。
- **grace 统一两种"二次出现"**：① 并发刷新的第二方；② 响应丢失后客户端重试。两者都走 `rotateFromTip`，沿链找到尖端续换。窗口外（超 `grace-seconds`）或跨 UA 才判 `reuse`。
- **USED 不立即删**：保留至原始 exp 用于复用/grace 判定；只 `HSET` 字段不动 TTL，到期 Redis 自动清，无堆积。
- **复用检测的边界**：能识别「USED 被复用」与「跨 UA 复用」；攻击者若偷走**当前 ACTIVE** 并抢先刷新，会沿正常路径成功（同 UA 时），与合法用户各自持有一支 lineage——这是轮换刷新的固有局限（§十）。UA 门控是其弱防线。
- **UA 绑定**：沿用 `LoginToken` 的 UA 校验哲学（弱绑定、防另一类设备直接重放），从请求头取，与签发一致。
- **调用方契约**：前端续期必须用「最新一次 refresh 响应」下发的 cookie（§九）。`MAX_CHAIN_HOPS` 取保守值（如 8）防异常长链。
- **`RotationVerdict.Rotate.remainingTtlMillis` 的用途**：Lua 返回的 `PTTL` 是**旧** token 的剩余 TTL，仅供日志/审计（确认旧记录按原始 exp 过期）；**新 refresh 的 TTL 由 `ttlSeconds(refreshIssued.expiresAt)` 重新计算**（= refresh 配置 TTL，不沿用旧 token 剩余），落新 ACTIVE 记录的 `EXPIRE`。两者不要混。

### 4.3 登出与改密撤销（`revokeAll` 语义迁移）

| 操作 | 现模型（单 token） | 双 token |
|---|---|---|
| `POST /api/auth/logout` | 删 `LoginToken` access 白名单 | `permitAll`；有 cookie 则 `RefreshTokenStore.revokeOne(jti, userId, familyId)` 删当前 refresh 记录 + 从两个索引 SET 移除 + 清 cookie；登出语义只删当前记录，**不动同族其他设备**（区别于复用检测的整族撤销）。可选 logoutAll 删该用户全部 |
| 改密 `revokeAll(userId)` | 删全部 `LoginToken` | 删该用户**全部 refresh**（`RefreshTokenStore.revokeAll`）；access 在 ≤30min 过期 |
| 即时撤 access | 删 Redis 即刻失效 | **不能**（无状态）→ 见 §十 `tokenEpoch` 可选增强 |

> `/api/auth/logout` 必须 `permitAll`：access 过期是用户点登出的常见场景，此时无有效 Bearer，必须凭 cookie 完成登出。§5.1 已将其放入 `permitAll`。cookie 可选（无 cookie 幂等返回成功）。
>
> `AuthServiceImpl.changePassword` 现调用 `loginTokenAuthentication.revokeAll(userId)` —— 实现迁移后该方法语义自动变为「撤销全部 refresh」，**调用点无需改动**（依赖的是抽象 `revokeAll`）。

---

## 五、Spring Security 集成

### 5.1 过滤器链（`config/SecurityConfig.kt`）

```kotlin
http
    .csrf { it.disable() }                                   // 无状态 JWT，CSRF 关闭；refresh cookie 靠 SameSite 防 CSRF（§6.3）
    .cors(Customizer.withDefaults())
    .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
    .authorizeHttpRequests {
        // 匿名：登录、刷新（读 cookie）、登出（读 cookie）、注册、验证码、找回
        it.requestMatchers(
            "/api/auth/login", "/api/auth/login/email", "/api/auth/refresh", "/api/auth/logout",
            "/api/auth/register/manager", "/api/auth/verification-code", "/api/auth/reset-password",
            "/api/users/Register", "/error",
        ).permitAll()
        // 【顺序敏感】项目方自服务仍走 controlPassword（非 JWT），必须在 /api/admin/** 之前放行，
        // 否则 hasRole('ADMIN') 会把它误拦（现状 SecurityConfig.kt:83 即此 permitAll）。
        it.requestMatchers("/api/admin/project/**").permitAll()
        // 其余管理端：凭角色（替代「anyRequest.authenticated + service 层再校验」的隐式做法）
        it.requestMatchers("/api/admin/**").hasRole("ADMIN")
        // 公开 GET（商品/标签/评价）、公开投稿、文件下载签名链路 ……（沿用现有 permitAll 列表）
        it.anyRequest().authenticated()
    }
    .exceptionHandling {
        it.authenticationEntryPoint { _, response, _ -> writeJson(response, 401, "Unauthorized") }
        // 新增：hasRole 拒绝时统一回 403 JSON（否则落到默认 HTML）
        it.accessDeniedHandler { _, response, _ -> writeJson(response, 403, "Forbidden") }
    }
    .addFilterBefore(
        JwtAuthenticationFilter(jwtService, devTokenManager),   // 去掉 loginTokenAuthentication 依赖
        UsernamePasswordAuthenticationFilter::class.java,
    )
```

> **`accessDeniedHandler` 必须补**：启用 `hasRole('ADMIN')` 后，角色不足默认返回 Spring Security 的 HTML 403，与本项目统一 JSON 响应体不符。`authenticationEntryPoint`（401）已存在，照搬到 `accessDeniedHandler`（403）即可。
>
> **路由顺序必须保留 `/api/admin/project/**` permitAll 在 `/api/admin/**` hasRole 之前**——这是现状 `SecurityConfig.kt:83` 的 controlPassword 自服务链路，顺序写反会直接 401/403 把它打断。

### 5.2 角色映射

- `JwtAuthenticationFilter` 从 access 的 `role` claim 构造 `SimpleGrantedAuthority("ROLE_ADMIN"|"ROLE_CUSTOMER")`。
- `SecurityConfig` 用 `.hasRole("ADMIN")`（Spring 自动加 `ROLE_` 前缀）。`AdminAccessService` 若仍有业务侧细化校验可保留，二者不冲突。
- `@AuthenticationPrincipal userId: Long` 取主体不变（`UsernamePasswordAuthenticationToken.principal = userId`）。

### 5.3 刷新/登出端点的鉴权

- `/api/auth/refresh`、`/api/auth/logout`：`permitAll()`。鉴权凭据是 **HttpOnly cookie 里的 refresh**，不是 Bearer；不能要求 `authenticated()`（否则过期 access 会挡住续期与登出）。
- `/api/auth/logout`：cookie 可选（有则删对应 refresh 记录，无则幂等返回成功）。

---

## 六、刷新令牌的传输与安全

### 6.1 HttpOnly Cookie（对齐 `SecurityConfig` 既有注释）

登录/续期成功时由 `RefreshCookieService`（或 `LoginTokenAuthentication` 内）写 cookie：

```kotlin
fun attachRefresh(response: HttpServletResponse, jwt: String) {
    val cookie = ResponseCookie.from(properties.refresh.cookie.name, jwt)
        .httpOnly(properties.refresh.cookie.httpOnly)
        .secure(properties.refresh.cookie.secure)
        .sameSite(properties.refresh.cookie.sameSite)   // Lax / Strict / None
        .path(properties.refresh.cookie.path)            // /api/auth
        .domain(properties.refresh.cookie.domain.ifEmpty { null })  // 留空=不写 domain
        .maxAge(Duration.ofSeconds(properties.refresh.ttlSeconds))
        .build()
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
}

fun clear(response: HttpServletResponse) { /* 同上，value=""、maxAge=ZERO */ }
```

- `Path=/api/auth`：浏览器**仅**在访问 `/api/auth/*` 时回传 refresh，降低泄漏面（日志、其他接口的 CORS 反射等）。
- `Secure=true`：生产只走 HTTPS。本地 http 调试用 `JWT_REFRESH_COOKIE_SECURE=false`（见 §7.3）。
- `HttpOnly`：JS 不可读，防 XSS 窃取。**access token 放内存（JS 变量），不放 localStorage**——localStorage 的 access 同样怕 XSS；放内存 + refresh 走 HttpOnly 是当前最佳实践，前端 `useHttp` 需配合（§9）。

### 6.2 跨域（CORS）、cookie 与 SameSite 拓扑

`SecurityConfig.corsConfigurationSource` 已 `allowCredentials = true` + `allowedOriginPatterns = ["*"]`。**带 cookie 的跨域**要求：

- 前端 fetch 必须 `credentials: 'include'`（`useHttp` 调整）。
- `allowedOriginPatterns` 用回显 origin（已满足），不能用字面 `*`（浏览器会拒）。现状已正确，无需改。
- 刷新端点属跨域带凭证请求，preflight 已覆盖（`allowedMethods` 含 POST；refresh 无自定义头，cookie 是自动头，无需额外加）。

**SameSite 与部署拓扑（关键）**：

- **同注册域**（前端 `admin.shopmall.com` + API `api.shopmall.com`，eTLD+1 相同）→ 浏览器视为 **same-site**，`SameSite=Lax` 会带 cookie 发 POST → 默认配置即可。
- **跨注册域**（如前端 `shopmall-admin.netlify.app` + API `api.shopmall.com`）→ 属 **cross-site**，`Lax` 会**拦掉**跨站 POST 的 cookie → 刷新永远 401。必须改 `SameSite=None; Secure`，且 cookie `domain` 留空（跨注册域不能共享 domain）。
- 上线前先确认前端与 API 是否同注册域，据此选 `Lax`（默认）或 `None;Secure`（§7.3 列了环境变量）。

### 6.3 CSRF 权衡

refresh 是 POST 且携带 cookie 凭证，理论上可被 CSRF。但：

- 响应体只回 **access token**，且带 `Access-Control-Allow-Origin: <具体 origin>` + `allowCredentials`——攻击者跨域发起的 CSRF 即便成功，**响应也被同源策略挡住无法读取**，拿不到新 access。
- `SameSite=Lax`（同注册域）阻止跨站 POST 的 cookie 回传；`None;Secure`（跨注册域）场景下建议叠加自定义请求头校验。
- 故 CSRF 对 refresh 的收益近零，`csrf.disable()` + SameSite 足够。跨注册域若要纵深防御，可在 refresh 端点额外要求自定义请求头（如 `X-Requested-With`，浏览器跨站简单表单不会带），代价极小。

### 6.4 类型隔离的执行点（重申）

| 位置 | 强制 |
|---|---|
| `JwtAuthenticationFilter` | `verify(token, ACCESS)` —— refresh 当 Bearer 被拒 |
| `/api/auth/refresh` | `verify(refresh, REFRESH)` —— access 当 refresh 被拒 |
| `JwtService.verify` | `expectedType != null && type != expectedType → null` |

三处任一缺失都等于让长有效期的 refresh 能当 access 用，**整套设计失效**。代码评审重点盯这里。

---

## 七、配置：环境变量注入 `application.yaml`

> 设计原则：每个可变量都暴露为 `${ENV:default}`，遵循项目 12-factor 风格（真实 OS/Docker 环境变量优先于 `.env`）。

### 7.1 `application.yaml` 追加（`shopmall.security.jwt` 下重构）

```yaml
shopmall:
  security:
    jwt:
      # 生产务必用 .env 覆盖为足够长的随机串（沿用）
      secret: "${JWT_SECRET:dev-secret-do-not-use-in-prod-please-override-via-JWT_SECRET}"
      # 访问令牌：短有效期，默认 30 分钟
      access:
        ttl-seconds: "${JWT_ACCESS_TTL_SECONDS:1800}"
      # 刷新令牌：长有效期，默认 7 天；原子轮换 + grace 重试 + 复用检测
      refresh:
        ttl-seconds: "${JWT_REFRESH_TTL_SECONDS:604800}"
        # true=每次刷新签发新 refresh 并作废旧 token（推荐）；false=刷新只换 access（复用检测失效）
        rotate: "${JWT_REFRESH_ROTATE:true}"
        # true=超 grace / 跨 UA 的 USED 再现即撤销整族（推荐）
        reuse-detect: "${JWT_REFRESH_REUSE_DETECT:true}"
        # USED token 在此窗口内再现视为合法重试（响应丢失重发 / 并发第二方），窗口外才判被盗
        grace-seconds: "${JWT_REFRESH_GRACE_SECONDS:30}"
        cookie:
          name: "${JWT_REFRESH_COOKIE_NAME:refresh_token}"
          # 留空=不写 domain（浏览器用当前 host）；同注册域多子域可配 .shopmall.com
          domain: "${JWT_REFRESH_COOKIE_DOMAIN:}"
          path: "${JWT_REFRESH_COOKIE_PATH:/api/auth}"
          secure: "${JWT_REFRESH_COOKIE_SECURE:true}"
          http-only: "${JWT_REFRESH_COOKIE_HTTP_ONLY:true}"
          # 同注册域=Lax；跨注册域必须 None（且 secure=true）。详见 §6.2
          same-site: "${JWT_REFRESH_COOKIE_SAME_SITE:Lax}"
      # 开发固定令牌（既有；仍是 access 语义，见 §八 DevTokenManager 改动）
      dev:
        fixed-token:
          enabled: "${DEV_FIXED_TOKEN_ENABLED:false}"
          jti: "${DEV_FIXED_TOKEN_JTI:00000000-0000-0000-0000-000000000000}"
          ttl-seconds: "${DEV_FIXED_TOKEN_TTL_SECONDS:315360000}"
        default-admin:
          username: "${DEV_ADMIN_USERNAME:admin}"
          password: "${DEV_ADMIN_PASSWORD:admin}"
          email: "${DEV_ADMIN_EMAIL:admin}"
```

> **迁移注意**：现有顶层 `shopmall.security.jwt.ttl-seconds`（259200）拆为 `access.ttl-seconds`。推荐**直接迁移**，发布说明里写清楚。

### 7.2 `JwtProperties` 重构

```kotlin
@ConfigurationProperties(prefix = "shopmall.security.jwt")
data class JwtProperties(
    val secret: String = "dev-secret-do-not-use-in-prod-please-override-via-JWT_SECRET",
    val access: Access = Access(),
    val refresh: Refresh = Refresh(),
) {
    data class Access(val ttlSeconds: Long = 1800L)

    data class Refresh(
        val ttlSeconds: Long = 604800L,
        val rotate: Boolean = true,
        val reuseDetect: Boolean = true,
        val graceSeconds: Long = 30L,
        val cookie: Cookie = Cookie(),
    )

    data class Cookie(
        val name: String = "refresh_token",
        val domain: String = "",
        val path: String = "/api/auth",
        val secure: Boolean = true,
        val httpOnly: Boolean = true,
        val sameSite: String = "Lax",   // Spring 的 ResponseCookie.sameSite 接受 Lax/Strict/None
    )
}
```

`JwtConfig` 的 `@Bean jwtService` 不变（仍注入 `secret`）；新增的 `access`/`refresh` 由 `RefreshTokenService`/`RefreshCookieService`/`RefreshTokenStore` 注入。`StringRedisTemplate` 由 Spring Boot 自动装配（项目仅覆写 `redisTemplate`，未覆写 `stringRedisTemplate`，故默认 bean 可用）。

### 7.3 `.env`（项目根，gitignore；`config.import: "optional:file:.env[.properties]"` 已加载）

```properties
# —— 双 Token 认证 ——
JWT_ACCESS_TTL_SECONDS=1800
JWT_REFRESH_TTL_SECONDS=604800
JWT_REFRESH_ROTATE=true
JWT_REFRESH_REUSE_DETECT=true
JWT_REFRESH_GRACE_SECONDS=30
JWT_REFRESH_COOKIE_SECURE=true
JWT_REFRESH_COOKIE_SAME_SITE=Lax
# 本地 http 调试时：
# JWT_REFRESH_COOKIE_SECURE=false
# 跨注册域前后端（不同 eTLD+1）时：
# JWT_REFRESH_COOKIE_SAME_SITE=None
```

> `JWT_REFRESH_COOKIE_SECURE` 本地默认 `true` 会导致 http 下浏览器不回传 cookie，刷新 401——**这是最易踩的坑**，本地 http 必须设 `false` 或用 HTTPS 反代。

---

## 八、待新增 / 修改文件清单

**新增**：

- `shared/RefreshTokenStore.kt` — `StringRedisTemplate` 封装：`issueActive` / `decideRotation`(跑 Lua) / `loadHash` / `revokeOne`(登出) / `revokeFamily` / `revokeAll`（§3.2）。`revokeFamily`/`revokeAll` 交叉清理对侧索引（读-删顺序见 §3.2）。
- `META-INF/scripts/refresh_rotate.lua` — 原子轮换脚本（§3.3）；随 jar 打包，启动 `DefaultRedisScript` 加载。
- `authentication/SecurityContextUser.kt` — `@Component`，从 `SecurityContextHolder` 反读 `userId`(principal) + `role`（`Role.fromAuthority` 归一 `ROLE_<role>` authority），供控制器/服务层取预设角色，无需查 DB（§4.1/§5.2）。`@AuthenticationPrincipal userId: Long` 取 ID 不变，本类是角色/权限预设值的统一读取器。
- `authentication/RefreshCookieService.kt` — `attachRefresh / clear`（`ResponseCookie`，§6.1）。

> **刷新逻辑的归属（方案 A，已采用）**：刷新与会话管理逻辑并入既有 `LoginTokenAuthenticationImpl`（`login`/`refresh`/`logout`/`revokeAll` 同处），`AuthController`/`AuthServiceImpl` 注入点不变。**不**新立 `RefreshTokenService`（方案 B 弃用）。

**修改**：

- `authentication/JwtService.kt` — `TokenType` 枚举；`issue` 增 `type/familyId/role`、`jti` 可显式传；`verify(token, expectedType)`；claims 增 `typ/fam/role` 与对应正则；**`role` 非已知角色 → 返回 null**（§3.1）。**保持无 Spring/Redis 依赖。**
- `authentication/JwtAuthenticationFilter.kt` — 构造去掉 `loginTokenAuthentication`；`verify(token, ACCESS)`；从 `role` claim 构造 `GrantedAuthority`（§4.1/§5.2）。
- `authentication/LoginTokenAuthentication.kt`（接口）+ `Impl` — `login` 改签 access+refresh（refresh 经 `RefreshTokenStore` 落 Redis）；新增 `refresh(...)`、`logout(...)`；`revokeAll` 语义改为撤销 refresh。`LoginResult.Response` 把单 `token` 换成 `access_token` + `expires_in`（refresh 不回 body）。`logout` 调 `store.revokeOne`（仅删当前记录，§4.3）。
- `entity/jdbc/User.kt` — `Role` 枚举加 `companion object { fun fromAuthority(roleName: String): Role? }`，供 `SecurityContextUser` 从 `ROLE_<role>` authority 反解角色（不查 DB，§5.2）。
- `controller/AuthController.kt` — 登录成功写 refresh cookie；新增 `POST /api/auth/refresh`（读 cookie → service.refresh → 回 access + 滚动 cookie）、`POST /api/auth/logout`（清记录 + 清 cookie）。
- `service/AuthService.kt` + `impl/AuthServiceImpl.kt` — `changePassword` 的 `revokeAll` 调用点不变（语义已迁移），无需改签名。
- `config/SecurityConfig.kt` — `/api/auth/refresh`、`/api/auth/logout` 入 `permitAll`；**保留 `/api/admin/project/**` permitAll 在 `/api/admin/**` hasRole 之前**；补 `accessDeniedHandler`（403 JSON）；`JwtAuthenticationFilter` 构造去 `loginTokenAuthentication`（§5.1）。
- `config/JwtProperties.kt` — 重构为 `access`/`refresh`/`refresh.{rotate,reuseDetect,graceSeconds,cookie}`（§7.2）。
- `src/main/resources/application.yaml` — §7.1；`.env` 模板追加 §7.3。

**DevTokenManager 需同步修改（非「不动」）**：`JwtService.issue` 签名变了，`DevTokenManager.kt:43` 的 `jwtService.issue(userId, properties.jti, properties.ttlSeconds)` 会**编译失败**（第二参现在是 `TokenType`）。需改为：

```kotlin
val issued = jwtService.issue(
    userId, TokenType.ACCESS, properties.ttlSeconds,
    role = Role.ADMIN.name, jti = properties.jti,
)
```

且固定令牌现在必须带 `typ=access`，否则 `JwtAuthenticationFilter` 里 `verify(token, ACCESS)` 的 `TYP_REGEX` 解析不到 typ 直接返回 null，旁路自己都过不了。`fixedTokenUserId(claims)` 也应断言 `claims?.type == TokenType.ACCESS`（且 `role` 合法），再返回管理员 userId。

**退役**：

- `entity/redis/LoginToken.kt` + `repository/LoginTokenRepository.kt` — 访问白名单退役。刷新职责已由 `RefreshTokenStore`（StringRedisTemplate）接管，**不**用 `@RedisHash`。建议保留一个版本作为灰度回退，确认无引用后删。

**组织决策（接口归属）**：刷新与会话管理逻辑放哪有两种选择——
- **A（推荐）**：并入 `LoginTokenAuthentication`（它本就是「会话与令牌生命周期」抽象），`login`/`refresh`/`logout`/`revokeAll` 同处，`AuthServiceImpl` 注入点不变。
- **B**：新立 `RefreshTokenService`，`LoginTokenAuthentication` 退化为只管 access 签发。
> 选 A 改动面小、调用方（`AuthController`/`AuthServiceImpl`）几乎无感，推荐。

---

## 九、前端协同（`AdminPanelUI` / `useHttp`）

- **access 放内存**：登录响应的 `access_token` 存 JS 内存（Pinia store），不放 `localStorage`（降 XSS 暴露）。
- **refresh 交 cookie**：后端 `Set-Cookie`（HttpOnly），前端无需读；fetch 全程 `credentials: 'include'`。
- **续期策略**：`useHttp` 拦截 401 → 调 `POST /api/auth/refresh`（带 cookie）→ 用新 `access_token` 重放原请求；refresh 也 401（refresh 失效/复用撤销）→ 跳登录页。
- **并发续期（单飞）**：多个请求同时 401 时，前端用「单 inflight refresh + 队列重放」避免并发刷新。后端 Lua 原子性 + grace 已保证「并发同 cookie 不会误撤销」（§4.2），但单飞仍能减少无谓的二次轮换与 lineage 分叉，强烈建议。
- **登出**：调 `POST /api/auth/logout`，清内存 access。

> 即便后端有 grace 兜底，前端单飞仍是推荐实践：减少 Redis 写入、减少会话族分叉、续期更快。

---

## 十、范围边界与后续迭代

> 本期为双 Token MVP，明确以下取舍：

- **即时撤销 access（行为变化，需知情）**：本设计 access 无状态，改密/登出后最长滞后 `access.ttl`（≤30min）才彻底失效——**相对现状（删 Redis 即刻 401）是回退**。对「怀疑被盗、紧急改密」场景，若业务要求即时失效，加 **per-user `tokenEpoch`**：`users` 表加 `token_epoch: Long`，access/refresh claim 内嵌签发时的 epoch，过滤器做 1 次 Redis/DB GET 比对当前 epoch（可缓存）。bump epoch = 立即作废该用户所有令牌。代价是恢复每请求 1 次查询（与现模型同量级）；建议至少在**改密路径**进 MVP，其余按需开启。
- **role 冻结**：access 内 role 在签发时固化，降级/升级最长滞后一个 access TTL。敏感操作可在 service 层二次读 DB 角色（如 `AdminAccessService` 既有模式）兜底。
- **复用检测的局限**：能识别「USED 被复用」「跨 UA 复用」「超 grace 复用」；无法识别「当前 ACTIVE token 被盗且攻击者同 UA 抢先刷新」（会正常成功，与合法用户各持一支 lineage）。增强需叠设备指纹/IP 风控（后续）。
- **grace 窗口的权衡**：窗口越长越能吸收网络抖动重试，但也越长地把「被盗 token 的二次使用」误判为合法重试。默认 30s 是经验值；高风险场景可调小并配合 UA/IP 风控。
- **多设备会话视图**：`refresh:idx:user:<userId>` 可列全部 jti，后续可做「已登录设备列表 / 远程踢出」（按族 `revokeFamily` 单踢某会话）。
- **刷新频率上限**：refresh 端点可加 per-user 限流（复用 `VerificationCodeRateLimitException` 模式 / Redis 计数），防恶意刷新轰炸。
- **Redis flush / `clear-on-startup=true`**：会清掉全部 `refresh:*` → 每个人下次刷新 `unknown` → 401 → 重登。本地 `clear-on-startup` 每次重启都触发；生产必须置 `false`。新性质：access 无状态，flush 后在途 access 仍可用至过期，然后才无法续期（「宽限期后掉线」，运维勿误判为 bug）。
- **`LoginToken` 清理**：灰度退役一个版本后删除旧 `@RedisHash` 残留（本地 `clear-on-startup` 会清，生产注意）。

---

## 十一、验证方案（端到端）

1. **编译与配置绑定**：`./gradlew bootRun`，确认 `JwtProperties` 绑定 `access.ttl-seconds`/`refresh.*`（含 `grace-seconds`）成功；故意配 `JWT_REFRESH_COOKIE_SECURE=false`（本地 http）确认 cookie 回传；确认 Lua 脚本随 jar 加载。
2. **登录**：`POST /api/auth/login` → 响应体含 `access_token`/`expires_in`、无 refresh；`Set-Cookie` 含 `refresh_token`（HttpOnly; Secure; SameSite; Path=/api/auth）。Redis 出现 `refresh:token:<jti>`(ACTIVE) + `refresh:idx:user:*` / `refresh:idx:fam:*`。
3. **受保护访问（无状态）**：用 access 调 `/api/auth/me`（或任意 authenticated 接口）→ 200；**停 Redis** 后再调仍 200（证明无状态）；access 用 `/api/auth/refresh` 的 refresh token 当 Bearer → 401（类型隔离）。
4. **续期轮换**：`POST /api/auth/refresh`（带 cookie）→ 新 access + 新 `Set-Cookie`；Lua 把旧 jti 的 HASH `status=USED`、`replacedBy=新jti`、`rotatedAt=<now>`，**TTL 未变**（`TTL refresh:token:<旧jti>` 与轮换前相等）；新 jti HASH ACTIVE（同 familyId）。
5. **并发同 token（原子性核心）**：同一 ACTIVE cookie 发起 2 个并发 `/api/auth/refresh`：第一个 `Rotate` 成功，第二个进 `Grace`、沿 `replacedBy` 续换成功，**两者都拿到有效新 token，整族未被撤销**（对比修复前：双双命中 ACTIVE → 分叉）。断言 Redis 中该 fam 的 lineage 链路 `replacedBy` 正确串联。
6. **grace 重试（响应丢失）**：记下旧 cookie，立即（< `grace-seconds`）再次 refresh → 成功续换（沿尖端），不撤销；把 `grace-seconds` 调到 1s，等窗口过期后再用同一旧 cookie → 401 且整族被删。
7. **复用检测（超窗口 / 被盗）**：USED token 超 grace 再现 → 401，Redis 中该 familyId 的**全部**记录被删（`revokeFamily`），日志含 `Refresh token reuse detected`。
8. **跨 UA 复用**：grace 窗口内用**不同 User-Agent**重放 USED token → 升格 reuse → 整族撤销（验证 §4.2 `rotateFromTip` 的 UA 门控）。
9. **登出**：`POST /api/auth/logout` → 当前 refresh 记录删除，`Set-Cookie: Max-Age=0`；后续 refresh → 401。access 过期场景（无 Bearer）凭 cookie 也能登出（验证 `permitAll`）。
10. **改密撤销**：`POST /api/auth/change-password` → 该用户全部 refresh 删除（`revokeAll`）；旧 access 在过期前仍可用（**记录此行为为预期，属 §十 行为变化**），过期后不可续期。
11. **Spring Security 角色 & 路由**：CUSTOMER 持 access 调 `/api/admin/**` → 403 JSON（`accessDeniedHandler`）；ADMIN 调 → 通过；**`/api/admin/project/**` 仍可用 controlPassword 访问（未被 hasRole 误拦，验证 §5.1 顺序）**。改 DB 角色后刷新 access 角色更新。
12. **Dev 旁路**：`DEV_FIXED_TOKEN_ENABLED=true` 启动，固定 token（`typ=access`、`role=ADMIN`）调受保护接口通过；用它调 `/api/auth/refresh` → 401（非 refresh 类型）。
13. **CORS + cookie**：跨域前端 `credentials:'include'` 刷新成功；`Access-Control-Allow-Origin` 回显具体 origin、`Allow-Credentials:true`。同注册域用 Lax、跨注册域用 None;Secure 各验一遍（§6.2）。
14. **SecurityContextUser 预设值反读**：登录后注入 `SecurityContextUser`，断言 `userId`/`role`/`isAdmin()` 与 access claim 内嵌值一致；CUSTOMER 的 `isAdmin()` 为 false、ADMIN 为 true；匿名请求（无 Bearer）`userId`/`role` 为 null、`requireUserId()` 抛异常。`Role.fromAuthority("ROLE_ADMIN")` 归一为 `ADMIN`、未知 authority 返回 null。

---

## 附录：本次修订要点（相对初稿）

1. **原子轮换**（§3.3/§4.2）：`ACTIVE→USED` 改由单条 Lua 原子完成，消除「并发刷新双双命中 ACTIVE、会话族分叉」的竞态；初稿的 `findById`+`save` 非原子。
2. **grace 窗口 + replacedBy**（§3.2/§4.2）：USED 在窗口内再现视为合法重试（响应丢失重发 / 并发第二方），窗口外或跨 UA 才判被盗；修复初稿「网络重试误判复用 → 用户被踢」。
3. **存储改 StringRedisTemplate + Lua**（§3.2）：弃用 `@RedisHash`，因默认 `RedisTemplate` 把 key JSON 序列化（`RedisTemplateConfig.kt:9-13`），Lua 无法可靠寻址；项目注释本就建议可读 key 用 StringRedisTemplate。
4. **USED 记录 TTL 不再被重置**（§3.2/§3.3）：轮换只 `HSET` 字段、不重写整实体、不发 `EXPIRE`，TTL 原样保留，修复初稿「USED 被 `save()` 重置成满 TTL 堆积」。
5. **路由顺序**（§5.1）：显式保留 `/api/admin/project/**` permitAll 在 `/api/admin/**` hasRole **之前**，避免误伤 controlPassword 自服务。
6. **DevTokenManager 改为需修改**（§八）：订正初稿「不动」的错误——`issue` 签名变化会编译失败，固定令牌必须带 `typ=access`+`role=ADMIN`，`fixedTokenUserId` 需校验类型。
7. **logout 鉴权统一为 permitAll**（§4.3/§5.3）：订正初稿「authenticated 或 cookie」的前后矛盾。
8. **role 校验防静默降级**（§3.1）：`ROLE_REGEX` 放宽，且未知角色令 `verify` 返回 null（而非回退 CUSTOMER）。
9. **SameSite 部署拓扑**（§6.2）：补「同注册域=Lax、跨注册域=None;Secure」的分叉说明与 `.env` 开关。
10. **`tokenEpoch` 提升为「至少改密路径进 MVP」的可选项**（§十）：明确改密不再即时吊销在途 access 是相对现状的回退，需知情决策。
11. **代码片段**：`nowUtc()` 等未定义 helper 改为可落地表达或显式标注，片段可直接对照实现。
