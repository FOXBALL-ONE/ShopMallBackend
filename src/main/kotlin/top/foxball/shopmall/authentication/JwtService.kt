package top.foxball.shopmall.authentication

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 令牌类型：`typ` claim 的取值，access 与 refresh 互不通用，由 [JwtService.verify] 的 `expectedType` 强校验。
 */
enum class TokenType { ACCESS, REFRESH }

/**
 * 纯逻辑 JWT（HS256，header.payload.signature）签发与校验。
 *
 * 不依赖 Spring、不读写 Redis，仅负责签名/验签；密钥由构造方注入（建议来自配置项，勿硬编码）。
 * 刷新令牌的轮换状态、会话族索引与撤销全部由调用方（`RefreshTokenStore` 等）经 Redis 管理，
 * 本类只负责：按 [TokenType] 决定写入哪些 claim、HS256 签发、验签 + 过期 + 类型 + role 白名单校验。
 *
 * 两类令牌共享同一 HS256 密钥（`shopmall.security.jwt.secret`），以 `typ` claim 区分：
 * - access：`sub`(userId) + `jti` + `typ=access` + `role` + `iat`/`exp`，短有效期（≤30min），无状态验签。
 * - refresh：`sub`(userId) + `jti` + `typ=refresh` + `fam`(族 UUID) + `iat`/`exp`，长有效期，jti 即 Redis 主键。
 *
 * 载荷中 `iat`/`exp` 为秒级 NumericDate（UTC 纪元秒），内存中以 [LocalDateTime]（UTC）表达，
 * 通过 [ZoneOffset.UTC] 与纪元秒互转并 [ChronoUnit.SECONDS] 截断，保证签发与回读的时间戳严格相等。
 * 验签失败 / 结构错误 / 过期 / 类型不符 / role 非法 一律返回 null，不抛异常外泄内部细节。
 */
class JwtService(secret: String) {

    private val hmacKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    private val headerSegment: String

    init {
        val headerJson = """{"alg":"HS256","typ":"JWT"}"""
        headerSegment = base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
    }

    /** 签发结果：携带原始 token 与时间戳，便于调用方落盘 Redis 与计算 TTL。 */
    data class IssuedToken(
        val token: String,
        val jti: String,
        val issuedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    )

    /**
     * 解析出的声明；验签失败、过期、类型不符或 role 非法时 [verify] 返回 null。
     *
     * - [familyId] 仅 refresh 有（会话族标识，复用检测按族撤销）；
     * - [role] 仅 access 有（映射为 Spring Security authority）。
     * 二者在对方令牌类型下为 null。
     */
    data class Claims(
        val userId: Long,
        val jti: String,
        val type: TokenType,
        val familyId: String?,
        val role: String?,
        val issuedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    )

    /**
     * 签发一张 JWT。[type] 决定写入哪些 claim：
     * - [TokenType.ACCESS]：写入 `role`，忽略 [familyId]；[role] 必须非 null 且在已知角色集内。
     * - [TokenType.REFRESH]：写入 `fam`，忽略 [role]；[familyId] 必须非 null。
     *
     * [jti] 可显式传入（刷新轮换时由调用方预生成，供 Lua 原子写入 `replacedBy`），缺省随机。
     * [ttlSeconds] 必须大于 0；[jti] 必须是合法 UUID；access 的 [role] 与 refresh 的 [familyId] 必须满足各自约束。
     * 调用方误把 refresh 传成 access（或反之）的参数会在 require 阶段被快速发现。
     */
    fun issue(
        userId: Long,
        type: TokenType,
        ttlSeconds: Long,
        familyId: String? = null,
        role: String? = null,
        jti: String = UUID.randomUUID().toString(),
    ): IssuedToken {
        require(ttlSeconds > 0) { "JWT 有效期必须大于 0" }
        require(runCatching { UUID.fromString(jti) }.isSuccess) { "jti 必须是合法 UUID" }
        when (type) {
            TokenType.ACCESS -> {
                require(role != null) { "access 令牌必须提供 role" }
                require(role in KNOWN_ROLES) { "access 令牌的 role 不在已知角色集内：$role" }
            }
            TokenType.REFRESH -> {
                require(familyId != null) { "refresh 令牌必须提供 familyId" }
                require(runCatching { UUID.fromString(familyId) }.isSuccess) { "familyId 必须是合法 UUID" }
            }
        }
        // iat/exp 约定为秒级，统一截断到秒，使签发与回读的时间戳严格相等；以 UTC 表达以匹配 JWT 纪元定义
        val now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        val exp = now.plusSeconds(ttlSeconds)
        val payload = buildPayload(userId, jti, type, familyId, role, now, exp)
        val payloadSegment = base64UrlEncode(payload.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerSegment.$payloadSegment"
        val signature = base64UrlEncode(sign(signingInput.toByteArray(StandardCharsets.UTF_8)))
        return IssuedToken(
            token = "$signingInput.$signature",
            jti = jti,
            issuedAt = now,
            expiresAt = exp,
        )
    }

    /**
     * 验签 + 过期 +（可选）类型校验；任一失败返回 null，不抛异常外泄内部细节。
     *
     * - 签名/结构/过期：常规校验。
     * - `typ` claim：必须存在且为 `access`/`refresh`，缺失或非法值返回 null。
     * - `role`（access）：必须存在且在 [KNOWN_ROLES] 白名单内，未知角色返回 null（防静默越权降级，见设计文档 §3.1）。
     * - `fam`（refresh）：必须存在且为合法 UUID。
     * - [expectedType]：非 null 时，`claims.type != expectedType` 即返回 null——这是防止 refresh 当 access（或反之）的最后防线。
     */
    fun verify(token: String, expectedType: TokenType? = null): Claims? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val signingInput = "${parts[0]}.${parts[1]}"
            // 常量时间比较签名，避免计时侧信道
            val expected = base64UrlEncode(sign(signingInput.toByteArray(StandardCharsets.UTF_8)))
            if (!MessageDigest.isEqual(
                    expected.toByteArray(StandardCharsets.UTF_8),
                    parts[2].toByteArray(StandardCharsets.UTF_8),
                )
            ) return null
            val payload = String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8)
            val claims = parseClaims(payload) ?: return null
            if (!LocalDateTime.now(ZoneOffset.UTC).isBefore(claims.expiresAt)) return null
            if (expectedType != null && claims.type != expectedType) return null
            claims
        } catch (e: Exception) {
            null
        }
    }

    private fun sign(input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(input)
    }

    /**
     * 按 [type] 决定字段：access 写 `role` 不写 `fam`；refresh 写 `fam` 不写 `role`。
     * `typ` 始终写入（类型强隔离的钥匙）。
     */
    private fun buildPayload(
        userId: Long,
        jti: String,
        type: TokenType,
        familyId: String?,
        role: String?,
        iat: LocalDateTime,
        exp: LocalDateTime,
    ): String {
        val iatSec = iat.toEpochSecond(ZoneOffset.UTC)
        val expSec = exp.toEpochSecond(ZoneOffset.UTC)
        return when (type) {
            TokenType.ACCESS -> {
                // role 由 issue 保证非 null
                """{"sub":"$userId","jti":"$jti","typ":"access","role":"$role","iat":$iatSec,"exp":$expSec}"""
            }
            TokenType.REFRESH -> {
                // familyId 由 issue 保证非 null
                """{"sub":"$userId","jti":"$jti","typ":"refresh","fam":"$familyId","iat":$iatSec,"exp":$expSec}"""
            }
        }
    }

    /**
     * 解析 payload 为 [Claims]。
     *
     * 校验顺序：`typ`（必填，缺失或非法 → null）→ `sub` → `jti`（UUID 合法性）→ `iat`/`exp`。
     * 再按类型分支：access 必须有 `role` 且在 [KNOWN_ROLES] 白名单内，未知或缺失 → null（防静默越权降级）；
     * refresh 必须有 `fam` 且为合法 UUID，缺失 → null。
     */
    private fun parseClaims(payload: String): Claims? {
        val typStr = TYP_REGEX.find(payload)?.groupValues?.get(1) ?: return null
        val type = when (typStr) {
            "access" -> TokenType.ACCESS
            "refresh" -> TokenType.REFRESH
            else -> return null
        }
        val sub = SUB_REGEX.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val jti = JTI_REGEX.find(payload)?.groupValues?.get(1) ?: return null
        runCatching { UUID.fromString(jti) }.getOrNull() ?: return null
        val iat = IAT_REGEX.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val exp = EXP_REGEX.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null

        val familyId: String?
        val role: String?
        when (type) {
            TokenType.ACCESS -> {
                // role 必须存在且在白名单内；未知角色直接 null，不回退 CUSTOMER（防静默越权降级）
                val r = ROLE_REGEX.find(payload)?.groupValues?.get(1) ?: return null
                if (r !in KNOWN_ROLES) return null
                role = r
                familyId = null
            }
            TokenType.REFRESH -> {
                val fam = FAM_REGEX.find(payload)?.groupValues?.get(1) ?: return null
                runCatching { UUID.fromString(fam) }.getOrNull() ?: return null
                familyId = fam
                role = null
            }
        }

        return Claims(
            userId = sub,
            jti = jti,
            type = type,
            familyId = familyId,
            role = role,
            issuedAt = LocalDateTime.ofEpochSecond(iat, 0, ZoneOffset.UTC),
            expiresAt = LocalDateTime.ofEpochSecond(exp, 0, ZoneOffset.UTC),
        )
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(segment: String): ByteArray =
        Base64.getUrlDecoder().decode(segment)

    private companion object {
        /** 已知角色白名单；新增角色时须同步此集合，否则带新角色的 access 会被 verify 判为非法（防静默越权降级）。 */
        val KNOWN_ROLES: Set<String> = setOf("CUSTOMER", "ADMIN")

        val SUB_REGEX = Regex(""""sub"\s*:\s*"?(\d+)"?""")
        val JTI_REGEX = Regex(""""jti"\s*:\s*"([0-9a-fA-F-]{36})"""")
        val IAT_REGEX = Regex(""""iat"\s*:\s*(\d+)""")
        val EXP_REGEX = Regex(""""exp"\s*:\s*(\d+)""")
        val TYP_REGEX = Regex(""""typ"\s*:\s*"(access|refresh)"""")
        val FAM_REGEX = Regex(""""fam"\s*:\s*"([0-9a-fA-F-]{36})"""")
        // role 放宽为大写+下划线，避免新增角色时正则失配；真正的约束放在 parseClaims 的白名单校验里
        val ROLE_REGEX = Regex(""""role"\s*:\s*"([A-Z_]+)"""")
    }
}
