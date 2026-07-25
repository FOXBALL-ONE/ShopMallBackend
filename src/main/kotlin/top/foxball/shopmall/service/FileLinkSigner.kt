package top.foxball.shopmall.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 为下载链接签发和校验短期 HMAC 签名，签名绑定文件、授权范围、到期时间和随机 nonce。 */
class FileLinkSigner(
    secret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

    /** URL 查询参数中需要返回的签名载荷。 */
    data class SignedLink(
        val scope: String,
        val nonce: String,
        val expiresAt: Instant,
        val signature: String,
    )

    /** 使用当前时钟计算到期时间，并为指定授权范围签发下载链接。 */
    fun sign(fileId: UUID, scope: String, ttlSeconds: Long): SignedLink {
        require(ttlSeconds > 0) { "File download link TTL must be positive." }
        require(scope.isNotBlank() && scope.length <= MAX_SCOPE_LENGTH) { "File download scope is invalid." }
        val expiresAt = Instant.now(clock).plusSeconds(ttlSeconds)
        val nonce = UUID.randomUUID().toString()
        return SignedLink(
            scope = scope,
            nonce = nonce,
            expiresAt = expiresAt,
            signature = signature(fileId, scope, expiresAt.epochSecond, nonce),
        )
    }

    /** 仅供内部调用兼容，签发结果仍使用新的 user scope 载荷。 */
    fun sign(fileId: UUID, userId: Long, ttlSeconds: Long): SignedLink =
        sign(fileId, "user:$userId", ttlSeconds)

    /** 使用常量时间比较校验签名；到期或任一绑定参数被篡改均返回 false。 */
    fun isValid(
        fileId: UUID,
        scope: String,
        expiresAtEpochSeconds: Long,
        nonce: String,
        suppliedSignature: String,
    ): Boolean {
        if (expiresAtEpochSeconds <= Instant.now(clock).epochSecond) return false
        if (scope.isBlank() || scope.length > MAX_SCOPE_LENGTH || nonce.isBlank() || nonce.length > MAX_NONCE_LENGTH) {
            return false
        }
        val expected = signature(fileId, scope, expiresAtEpochSeconds, nonce)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            suppliedSignature.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    private fun signature(fileId: UUID, scope: String, expiresAtEpochSeconds: Long, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val payload = "$fileId:$scope:$expiresAtEpochSeconds:$nonce"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    private companion object {
        const val MAX_SCOPE_LENGTH = 128
        const val MAX_NONCE_LENGTH = 64
    }
}
