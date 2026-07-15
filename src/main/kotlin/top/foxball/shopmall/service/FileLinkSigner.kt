package top.foxball.shopmall.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 为下载链接签发和校验短期 HMAC 签名，签名同时绑定文件、用户和到期时间。 */
class FileLinkSigner(
    secret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

    /** URL 查询参数中需要返回的签名载荷。 */
    data class SignedLink(
        val userId: Long,
        val expiresAt: Instant,
        val signature: String,
    )

    /** 使用当前时钟计算到期时间，并签发指定用户的下载链接签名。 */
    fun sign(fileId: UUID, userId: Long, ttlSeconds: Long): SignedLink {
        require(ttlSeconds > 0) { "File download link TTL must be positive." }
        val expiresAt = Instant.now(clock).plusSeconds(ttlSeconds)
        return SignedLink(
            userId = userId,
            expiresAt = expiresAt,
            signature = signature(fileId, userId, expiresAt.epochSecond),
        )
    }

    /** 使用常量时间比较校验签名；到期或任一绑定参数被篡改均返回 false。 */
    fun isValid(fileId: UUID, userId: Long, expiresAtEpochSeconds: Long, suppliedSignature: String): Boolean {
        if (expiresAtEpochSeconds <= Instant.now(clock).epochSecond) return false
        val expected = signature(fileId, userId, expiresAtEpochSeconds)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            suppliedSignature.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    private fun signature(fileId: UUID, userId: Long, expiresAtEpochSeconds: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val payload = "$fileId:$userId:$expiresAtEpochSeconds"
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }
}
