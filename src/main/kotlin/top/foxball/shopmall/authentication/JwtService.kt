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
 * 纯逻辑 JWT（HS256，header.payload.signature）签发与校验。
 *
 * 不依赖 Spring、不读写 Redis，仅负责签名/验签；密钥由构造方注入（建议来自配置项，勿硬编码）。
 * 会话元数据落盘与白名单（撤销）查询由调用方配合 [top.foxball.shopmall.entity.redis.LoginToken] 完成。
 *
 * 仅承载最小声明集：`sub`(userId)、`jti`、`iat`、`exp`。`jti` 为每次签发生成的随机 UUID，
 * 用于在 Redis 中唯一标识可撤销会话。载荷中 `iat`/`exp` 为秒级 NumericDate（UTC 纪元秒），
 * 内存中以 [LocalDateTime]（UTC）表达，通过 [ZoneOffset.UTC] 与纪元秒互转，保证与 JWT 时间定义一致。
 * 验签失败 / 结构错误 / 过期一律返回 null。
 */
class JwtService(secret: String) {

    private val hmacKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    private val headerSegment: String

    init {
        val headerJson = """{"alg":"HS256","typ":"JWT"}"""
        headerSegment = base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
    }

    /** 签发结果：携带原始 token 与时间戳，便于调用方落盘白名单。 */
    data class IssuedToken(
        val token: String,
        val jti: String,
        val issuedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    )

    /** 解析出的声明；验签失败或过期时为 null。 */
    data class Claims(
        val userId: Long,
        val jti: String,
        val issuedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    )

    /** 签发一张有效期 [ttlSeconds] 秒、绑定 [userId] 的 JWT（随机 jti，业务登录会话用）。 */
    fun issue(userId: Long, ttlSeconds: Long): IssuedToken =
        issue(userId, UUID.randomUUID().toString(), ttlSeconds)

    /**
     * 签发一张使用指定 [jti] 的 JWT；供需要确定性令牌的场景（如开发环境固定令牌）使用。
     * 业务登录会话应调用 [issue] 走随机 jti，避免令牌可重放。
     */
    fun issue(userId: Long, jti: String, ttlSeconds: Long): IssuedToken {
        require(ttlSeconds > 0) { "JWT 有效期必须大于 0" }
        require(runCatching { UUID.fromString(jti) }.isSuccess) { "jti 必须是合法 UUID" }
        // iat/exp 约定为秒级，统一截断到秒，使签发与回读的时间戳严格相等；以 UTC 表达以匹配 JWT 纪元定义
        val now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        val exp = now.plusSeconds(ttlSeconds)
        val payload = buildPayload(userId, jti, now, exp)
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

    /** 验签并校验过期；任一环节失败返回 null，不抛异常外泄内部细节。 */
    fun verify(token: String): Claims? {
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

    private fun buildPayload(userId: Long, jti: String, iat: LocalDateTime, exp: LocalDateTime): String =
        """{"sub":"$userId","jti":"$jti","iat":${iat.toEpochSecond(ZoneOffset.UTC)},"exp":${exp.toEpochSecond(ZoneOffset.UTC)}}"""

    private fun parseClaims(payload: String): Claims? {
        val sub = SUB_REGEX.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val jti = JTI_REGEX.find(payload)?.groupValues?.get(1) ?: return null
        runCatching { UUID.fromString(jti) }.getOrNull() ?: return null
        val iat = IAT_REGEX.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val exp = EXP_REGEX.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return Claims(
            userId = sub,
            jti = jti,
            issuedAt = LocalDateTime.ofEpochSecond(iat, 0, ZoneOffset.UTC),
            expiresAt = LocalDateTime.ofEpochSecond(exp, 0, ZoneOffset.UTC),
        )
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(segment: String): ByteArray =
        Base64.getUrlDecoder().decode(segment)

    private companion object {
        val SUB_REGEX = Regex(""""sub"\s*:\s*"?(\d+)"?""")
        val JTI_REGEX = Regex(""""jti"\s*:\s*"([0-9a-fA-F-]{36})"""")
        val IAT_REGEX = Regex(""""iat"\s*:\s*(\d+)""")
        val EXP_REGEX = Regex(""""exp"\s*:\s*(\d+)""")
    }
}
