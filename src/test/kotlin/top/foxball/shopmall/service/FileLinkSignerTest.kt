package top.foxball.shopmall.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 文件签名的有效性、scope/nonce 绑定、文件绑定与到期边界测试。 */
class FileLinkSignerTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val signer = FileLinkSigner(
        secret = "test-file-signing-secret",
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `accepts an unexpired signature for the original file scope and nonce`() {
        val fileId = UUID.randomUUID()
        val link = signer.sign(fileId, scope = "user:42", ttlSeconds = 300)

        assertTrue(
            signer.isValid(fileId, link.scope, link.expiresAt.epochSecond, link.nonce, link.signature),
        )
    }

    @Test
    fun `rejects a signature when scope nonce file signature or expiration changes`() {
        val fileId = UUID.randomUUID()
        val link = signer.sign(fileId, scope = "user:42", ttlSeconds = 300)

        assertFalse(signer.isValid(fileId, "user:43", link.expiresAt.epochSecond, link.nonce, link.signature))
        assertFalse(signer.isValid(fileId, link.scope, link.expiresAt.epochSecond, UUID.randomUUID().toString(), link.signature))
        assertFalse(signer.isValid(UUID.randomUUID(), link.scope, link.expiresAt.epochSecond, link.nonce, link.signature))
        assertFalse(signer.isValid(fileId, link.scope, link.expiresAt.epochSecond, link.nonce, "${link.signature}x"))
        assertFalse(signer.isValid(fileId, link.scope, now.epochSecond, link.nonce, link.signature))
    }
}
