package top.foxball.shopmall.authentication

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.foxball.shopmall.config.JwtProperties
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.TokenInvalidException
import top.foxball.shopmall.handler.UserDisabledException
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.shared.RefreshTokenStore
import top.foxball.shopmall.shared.RefreshTokenStore.RotationVerdict
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 双 Token 会话与令牌生命周期实现。
 *
 * - [login]：签发 access（typ=access，内嵌 role）+ refresh（typ=refresh，绑定 familyId），refresh 经
 *   [RefreshTokenStore.issueActive] 落 Redis；响应体只回 access，refresh 由控制器写 HttpOnly Cookie。
 * - [refresh]：原子轮换 + grace 重试 + 复用检测（见 [refreshFromCookie] / [rotateFromTip]）。原子性收口在
 *   [RefreshTokenStore.decideRotation] 的单条 Lua；并发同 token 的第二方进 grace，沿 `replacedBy` 续换尖端，
 *   不会双双命中 ACTIVE、不会误撤销整族。
 * - [logout] / [revokeAll]：撤销 refresh；access 无状态、在 ≤ [JwtProperties.Access.ttlSeconds] 内自然过期。
 *
 * 设计详见 `docs/dual-token-auth-design.md` §4.2。
 */
@Service
class LoginTokenAuthenticationImpl(
    private val jwtService: JwtService,
    private val jwtProperties: JwtProperties,
    private val userRepository: UserRepository,
    private val store: RefreshTokenStore,
) : LoginTokenAuthentication {

    override fun login(user: User, userAgent: String): LoginTokenAuthentication.LoginResult {
        // 凭据已由 AuthService 校验，此处仅签发令牌并组装登录响应
        val userId = user.id!!
        val familyId = UUID.randomUUID().toString()
        // access：短有效期、内嵌 role；refresh：长有效期、绑定 familyId（同族刷新不换）
        val access = jwtService.issue(
            userId, TokenType.ACCESS, jwtProperties.access.ttlSeconds,
            role = user.role.name,
        )
        val refresh = jwtService.issue(
            userId, TokenType.REFRESH, jwtProperties.refresh.ttlSeconds,
            familyId = familyId,
        )
        // refresh 落 Redis（ACTIVE）+ 索引；TTL = refresh 剩余有效期（至少 1s）
        store.issueActive(
            refresh.jti, userId, familyId, userAgent,
            ttlSeconds(refresh.expiresAt),
        )
        return LoginTokenAuthentication.LoginResult(
            state = LoginTokenAuthentication.LoginResult.State.SUCCESS,
            response = buildResponse(user, access.token, jwtProperties.access.ttlSeconds),
            refreshJwt = refresh.token,
        )
    }

    override fun refresh(refreshJwt: String, userAgent: String): LoginTokenAuthentication.RefreshResult =
        refreshFromCookie(refreshJwt, userAgent)

    override fun logout(refreshJwt: String?) {
        // 无 cookie 或令牌无效：幂等返回（登出不应因令牌过期而失败）
        if (refreshJwt.isNullOrBlank()) return
        val claims = jwtService.verify(refreshJwt, TokenType.REFRESH) ?: return
        val familyId = claims.familyId ?: return
        // 只删当前 refresh 记录（登出语义，区别于复用检测的整族撤销）；access 在过期前仍可用
        store.revokeOne(claims.jti, claims.userId, familyId)
    }

    override fun revokeAll(userId: Long) {
        store.revokeAll(userId)
    }

    // ---- 刷新编排（§4.2）----

    private fun refreshFromCookie(
        refreshJwt: String,
        requestUA: String,
    ): LoginTokenAuthentication.RefreshResult {
        // ① 签名 + 过期 + 类型必须是 refresh
        val claims = jwtService.verify(refreshJwt, TokenType.REFRESH) ?: throw TokenInvalidException()
        val familyId = claims.familyId ?: throw TokenInvalidException()

        // ② 用户态校验：用户存在且启用
        val user = userRepository.findById(claims.userId).orElse(null) ?: throw TokenInvalidException()
        if (!user.enabled) throw UserDisabledException()

        // ③ 预生成 newJti，让 Lua 能原子写入 replacedBy
        val newJti = UUID.randomUUID().toString()
        return when (val v = store.decideRotation(claims.jti, newJti, nowEpoch())) {
            is RotationVerdict.Unknown -> throw TokenInvalidException()
            is RotationVerdict.Reuse -> {
                if (jwtProperties.refresh.reuseDetect) {
                    store.revokeFamily(familyId)
                    log.warn("Refresh token reuse detected: family={} user={}", familyId, claims.userId)
                }
                // 对外统一 401，不泄露原因
                throw TokenInvalidException()
            }
            is RotationVerdict.Grace -> rotateFromTip(claims, user, requestUA, familyId, v.replacedBy)
            is RotationVerdict.Rotate -> doRotate(user, familyId, requestUA, claims.jti, newJti)
        }
    }

    /**
     * grace：沿 [replacedBy] 找到当前 ACTIVE 尖端（容忍多跳），从尖端轮换；UA / userId 必须一致，
     * 否则升格 reuse 撤销整族。
     *
     * 并发安全：找到 ACTIVE 尖端后，在 `decideRotation` 把它标 USED 与本请求执行之间，可能已有另一并发
     * 请求先把尖端标成 USED（返回 `Grace`）。此时不应撤销整族——应沿尖端新写入的 `replacedBy` 继续下钻
     * 找下一个 ACTIVE 尖端再轮换。仅 `Reuse`（超窗口）/`Unknown`（尖端过期/被撤销）才撤销整族。
     */
    private fun rotateFromTip(
        claims: JwtService.Claims,
        user: User,
        requestUA: String,
        familyId: String,
        startReplacedBy: String,
    ): LoginTokenAuthentication.RefreshResult {
        var jti = startReplacedBy
        repeat(MAX_CHAIN_HOPS) {
            val hash = store.loadHash(jti) ?: return reuseAndRevoke(claims, familyId)  // 尖端已过期/被撤销
            // 非尖端：继续沿 replacedBy 下钻
            if (hash["status"] != "ACTIVE") {
                val next = hash["replacedBy"]?.takeIf { it.isNotBlank() }
                if (next == null) return reuseAndRevoke(claims, familyId)
                jti = next
                return@repeat
            }
            // 找到 ACTIVE 尖端：UA / userId 不一致即视为被盗
            if (hash["userAgent"] != requestUA) return reuseAndRevoke(claims, familyId)
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
                    jti = next
                    return@repeat
                }
                // 超 grace 窗口的二次出现 / 尖端已失效 → 被盗，撤销整族
                else -> return reuseAndRevoke(claims, familyId)
            }
        }
        // 超过最大跳数仍未找到可用尖端 → 视为异常链路，撤销整族
        return reuseAndRevoke(claims, familyId)
    }

    /** 复用检测命中：撤销整族并对外回 401。 */
    private fun reuseAndRevoke(
        claims: JwtService.Claims,
        familyId: String,
    ): LoginTokenAuthentication.RefreshResult {
        if (jwtProperties.refresh.reuseDetect) {
            store.revokeFamily(familyId)
            log.warn("Refresh token reuse detected (grace/UA mismatch): family={} user={}", familyId, claims.userId)
        }
        throw TokenInvalidException()
    }

    /**
     * 真正签发：Lua 已把 [oldJti] 原子标 USED；这里签新 access + 新 refresh（用预生成 [newJti]），
     * 并落新 ACTIVE 记录。
     */
    private fun doRotate(
        user: User,
        familyId: String,
        requestUA: String,
        oldJti: String,
        newJti: String,
    ): LoginTokenAuthentication.RefreshResult {
        val userId = user.id!!
        val access = jwtService.issue(
            userId, TokenType.ACCESS, jwtProperties.access.ttlSeconds,
            role = user.role.name,   // 刷新时按 DB 当前角色重铸，降级最长滞后一个 access TTL
        )
        val refreshIssued = jwtService.issue(
            userId, TokenType.REFRESH, jwtProperties.refresh.ttlSeconds,
            familyId = familyId, jti = newJti,
        )
        store.issueActive(
            newJti, userId, familyId, requestUA,
            ttlSeconds(refreshIssued.expiresAt),
        )
        log.debug("Rotated refresh family={} old={} new={}", familyId, oldJti, newJti)
        return LoginTokenAuthentication.RefreshResult(
            accessToken = access.token,
            expiresIn = jwtProperties.access.ttlSeconds,
            refreshJwt = refreshIssued.token,
        )
    }

    private fun buildResponse(
        user: User,
        accessToken: String,
        expiresIn: Long,
    ): LoginTokenAuthentication.LoginResult.Response =
        LoginTokenAuthentication.LoginResult.Response(
            accessToken = accessToken,
            expiresIn = expiresIn,
            userId = user.id!!,
            // TODO: frp_token / group / traffic / tunnel 配额待对应模块落地后接入真实数据
            frpToken = "",
            userInfo = LoginTokenAuthentication.LoginResult.Response.UserInfo(
                username = user.username,
                email = user.email,
                limit = LoginTokenAuthentication.LoginResult.Response.Limit(
                    tunnel = null,
                    inbound = DEFAULT_TRAFFIC_LIMIT,
                    outbound = DEFAULT_TRAFFIC_LIMIT,
                ),
                avatar = "",
                traffic = BigDecimal.ZERO,
                registerTime = user.createdAt!!,
                group = LoginTokenAuthentication.LoginResult.Response.Group(
                    id = DEFAULT_GROUP_ID,
                    name = DEFAULT_GROUP_NAME,
                ),
            ),
        )

    /** refresh 剩余有效期（秒），至少 1s；供 store 落 TTL。 */
    private fun ttlSeconds(refreshExpiresAt: LocalDateTime): Long =
        ChronoUnit.SECONDS.between(LocalDateTime.now(ZoneOffset.UTC), refreshExpiresAt)
            .coerceAtLeast(1)

    private fun nowEpoch(): Long = LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(ZoneOffset.UTC)

    private companion object {
        val log = LoggerFactory.getLogger(LoginTokenAuthenticationImpl::class.java)
        const val DEFAULT_TRAFFIC_LIMIT = 0L
        const val DEFAULT_GROUP_ID = 0L
        const val DEFAULT_GROUP_NAME = "default"
        // grace 链下钻的最大跳数，防异常长链
        const val MAX_CHAIN_HOPS = 8
    }
}
