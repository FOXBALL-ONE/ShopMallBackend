package top.foxball.shopmall.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 文件签名的有效性、用户绑定、文件绑定与到期边界测试。 */
class FileLinkSignerTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val signer = FileLinkSigner(
        secret = "test-file-signing-secret",
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `accepts an unexpired signature for the original file and user`() {
        val fileId = UUID.randomUUID()
        val link = signer.sign(fileId, userId = 42, ttlSeconds = 300)

        assertTrue(signer.isValid(fileId, 42, link.expiresAt.epochSecond, link.signature))
    }

    @Test
    fun `rejects a signature when the user, file, signature or expiration changes`() {
        val fileId = UUID.randomUUID()
        val link = signer.sign(fileId, userId = 42, ttlSeconds = 300)

        assertFalse(signer.isValid(fileId, 43, link.expiresAt.epochSecond, link.signature))
        assertFalse(signer.isValid(UUID.randomUUID(), 42, link.expiresAt.epochSecond, link.signature))
        assertFalse(signer.isValid(fileId, 42, link.expiresAt.epochSecond, "${link.signature}x"))
        assertFalse(signer.isValid(fileId, 42, now.epochSecond, link.signature))
    }
}
