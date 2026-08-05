package top.foxball.shopmall.service

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.foxball.shopmall.config.SupportTicketProperties
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.handler.IdempotencyConflictException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.SupportTicketAttachmentLimitException
import top.foxball.shopmall.handler.SupportTicketRateLimitException
import top.foxball.shopmall.handler.SupportTicketRequestInProgressException
import top.foxball.shopmall.handler.SupportTicketUnsafeAttachmentException
import top.foxball.shopmall.repository.SupportTicketMessageAttachmentRepository
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SupportTicketRequestProtectionTest {
    private val redis = mock(StringRedisTemplate::class.java)
    private val attachmentRepository = mock(SupportTicketMessageAttachmentRepository::class.java)

    @Test
    fun `idempotency key accepts bounded safe values and trims whitespace`() {
        val protection = protection()

        assertEquals("ticket-key_001", protection.normalizeIdempotencyKey("  ticket-key_001  "))
        assertFailsWith<ParamErrorException> { protection.normalizeIdempotencyKey("short") }
        assertFailsWith<ParamErrorException> { protection.normalizeIdempotencyKey("ticket key 001") }
    }

    @Test
    fun `acquire maps new pending completed and conflicting Redis states`() {
        val protection = protection()
        `when`(
            redis.execute<String>(
                any(),
                any<List<String>>(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn("A", "P", "D:42", "C")

        assertIs<SupportTicketRequestProtection.Acquisition.Acquired>(
            protection.acquire("create", 7, "ticket-key-001", "fingerprint"),
        )
        assertEquals(
            SupportTicketRequestProtection.Acquisition.Pending,
            protection.acquire("create", 7, "ticket-key-001", "fingerprint"),
        )
        assertEquals(
            SupportTicketRequestProtection.Acquisition.Completed(42),
            protection.acquire("create", 7, "ticket-key-001", "fingerprint"),
        )
        assertFailsWith<IdempotencyConflictException> {
            protection.acquire("create", 7, "ticket-key-001", "different")
        }
    }

    @Test
    fun `acquire fails closed when Redis returns no state`() {
        val protection = protection()
        `when`(
            redis.execute<String>(
                any(),
                any<List<String>>(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(null)

        assertFailsWith<SupportTicketRequestInProgressException> {
            protection.acquire("create", 7, "ticket-key-001", "fingerprint")
        }
    }

    @Test
    fun `idempotency completion is deferred until transaction commit`() {
        val protection = protection()
        val token = SupportTicketRequestProtection.Token("redis-key", "pending", "completed:")
        `when`(
            redis.execute<Long>(
                any(),
                any<List<String>>(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(1L)
        TransactionSynchronizationManager.initSynchronization()
        try {
            protection.completeAfterCommit(token, 42)
            verifyNoInteractions(redis)

            val synchronizations = TransactionSynchronizationManager.getSynchronizations()
            synchronizations.forEach(TransactionSynchronization::afterCommit)
            verify(redis).execute<Long>(
                any(),
                any<List<String>>(),
                any(),
                any(),
                any(),
            )
            synchronizations.forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `idempotency reservation is released when transaction rolls back`() {
        val protection = protection()
        val token = SupportTicketRequestProtection.Token("redis-key", "pending", "completed:")
        TransactionSynchronizationManager.initSynchronization()
        try {
            protection.completeAfterCommit(token, 42)
            verifyNoInteractions(redis)

            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }

            verify(redis).execute<Long>(
                any(),
                any<List<String>>(),
                any(),
            )
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `fingerprint accepts supported signatures and changes with request content`() {
        val protection = protection()
        val png = MockMultipartFile(
            "files",
            "image.png",
            "image/png",
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2),
        )
        val pdf = MockMultipartFile(
            "files",
            "document.pdf",
            "application/pdf",
            "prefix%PDF-1.7 body".toByteArray(),
        )
        val text = MockMultipartFile("files", "notes.txt", "text/plain", "plain text".toByteArray())

        val first = protection.fingerprint(listOf("message"), listOf(png, pdf, text))
        val replay = protection.fingerprint(listOf("message"), listOf(png, pdf, text))
        val changed = protection.fingerprint(listOf("another message"), listOf(png, pdf, text))

        assertEquals(first, replay)
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
    }

    @Test
    fun `fingerprint rejects unsupported extension MIME mismatch and invalid magic`() {
        val protection = protection()
        val unsupported = MockMultipartFile("files", "payload.exe", "application/octet-stream", byteArrayOf(1))
        val wrongMime = MockMultipartFile(
            "files",
            "image.png",
            "image/jpeg",
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        )
        val wrongMagic = MockMultipartFile("files", "image.png", "image/png", "not a png".toByteArray())

        assertFailsWith<SupportTicketUnsafeAttachmentException> {
            protection.fingerprint(emptyList(), listOf(unsupported))
        }
        assertFailsWith<SupportTicketUnsafeAttachmentException> {
            protection.fingerprint(emptyList(), listOf(wrongMime))
        }
        assertFailsWith<SupportTicketUnsafeAttachmentException> {
            protection.fingerprint(emptyList(), listOf(wrongMagic))
        }
    }

    @Test
    fun `fingerprint rejects empty binary text and EICAR content`() {
        val protection = protection()
        val empty = MockMultipartFile("files", "empty.txt", "text/plain", byteArrayOf())
        val binaryText = MockMultipartFile("files", "binary.txt", "text/plain", byteArrayOf(1, 0, 2))
        val eicar = MockMultipartFile(
            "files",
            "eicar.txt",
            "text/plain",
            EICAR.toByteArray(StandardCharsets.US_ASCII),
        )

        assertFailsWith<SupportTicketUnsafeAttachmentException> {
            protection.fingerprint(emptyList(), listOf(empty))
        }
        assertFailsWith<SupportTicketUnsafeAttachmentException> {
            protection.fingerprint(emptyList(), listOf(binaryText))
        }
        assertFailsWith<SupportTicketUnsafeAttachmentException> {
            protection.fingerprint(emptyList(), listOf(eicar))
        }
    }

    @Test
    fun `fingerprint enforces per message attachment count and bytes`() {
        val properties = SupportTicketProperties(
            maxFilesPerMessage = 1,
            maxAttachmentBytesPerMessage = 4,
            maxMessageRequestBytes = 10,
        )
        val protection = protection(properties)
        val small = MockMultipartFile("files", "one.txt", "text/plain", "1234".toByteArray())
        val second = MockMultipartFile("files", "two.txt", "text/plain", "1".toByteArray())
        val tooLarge = MockMultipartFile("files", "large.txt", "text/plain", "12345".toByteArray())

        assertFailsWith<SupportTicketAttachmentLimitException> {
            protection.fingerprint(emptyList(), listOf(small, second))
        }
        assertFailsWith<SupportTicketAttachmentLimitException> {
            protection.fingerprint(emptyList(), listOf(tooLarge))
        }
    }

    @Test
    fun `attachment quota rejects ticket count and byte overages`() {
        val properties = SupportTicketProperties(
            maxAttachmentBytesPerMessage = 50,
            maxMessageRequestBytes = 60,
            maxAttachmentsPerTicket = 3,
            maxAttachmentBytesPerTicket = 100,
            maxAttachmentsPerCustomer = 5,
            maxAttachmentBytesPerCustomer = 200,
        )
        val protection = protection(properties)
        val file = MockMultipartFile("files", "evidence.txt", "text/plain", ByteArray(20) { 'a'.code.toByte() })
        `when`(attachmentRepository.countForTicket(3)).thenReturn(3, 0)
        `when`(attachmentRepository.totalBytesForTicket(3)).thenReturn(0, 90)

        assertFailsWith<SupportTicketAttachmentLimitException> {
            protection.requireAttachmentQuota(7, SupportTicketMessageSender.CUSTOMER, 3, listOf(file))
        }
        assertFailsWith<SupportTicketAttachmentLimitException> {
            protection.requireAttachmentQuota(7, SupportTicketMessageSender.CUSTOMER, 3, listOf(file))
        }
    }

    @Test
    fun `attachment quota comparison cannot be bypassed by long overflow`() {
        val properties = SupportTicketProperties(
            maxAttachmentBytesPerMessage = 50,
            maxMessageRequestBytes = 60,
            maxAttachmentsPerTicket = 3,
            maxAttachmentBytesPerTicket = 100,
            maxAttachmentsPerCustomer = 5,
            maxAttachmentBytesPerCustomer = 200,
        )
        val protection = protection(properties)
        val file = MockMultipartFile("files", "evidence.txt", "text/plain", byteArrayOf(1))
        `when`(attachmentRepository.countForTicket(3)).thenReturn(0)
        `when`(attachmentRepository.totalBytesForTicket(3)).thenReturn(Long.MAX_VALUE)

        assertFailsWith<SupportTicketAttachmentLimitException> {
            protection.requireAttachmentQuota(99, SupportTicketMessageSender.ADMIN, 3, listOf(file))
        }
    }

    @Test
    fun `attachment quota applies customer totals but not admin totals`() {
        val properties = SupportTicketProperties(
            maxAttachmentBytesPerMessage = 50,
            maxMessageRequestBytes = 60,
            maxAttachmentsPerTicket = 3,
            maxAttachmentBytesPerTicket = 100,
            maxAttachmentsPerCustomer = 5,
            maxAttachmentBytesPerCustomer = 200,
        )
        val customerProtection = protection(properties)
        val file = MockMultipartFile("files", "evidence.txt", "text/plain", ByteArray(20) { 'a'.code.toByte() })
        `when`(attachmentRepository.countForTicket(3)).thenReturn(0)
        `when`(attachmentRepository.totalBytesForTicket(3)).thenReturn(0)
        `when`(attachmentRepository.countForSender(7, SupportTicketMessageSender.CUSTOMER)).thenReturn(5)
        `when`(attachmentRepository.totalBytesForSender(7, SupportTicketMessageSender.CUSTOMER)).thenReturn(0)

        assertFailsWith<SupportTicketAttachmentLimitException> {
            customerProtection.requireAttachmentQuota(7, SupportTicketMessageSender.CUSTOMER, 3, listOf(file))
        }

        val adminRepository = mock(SupportTicketMessageAttachmentRepository::class.java)
        `when`(adminRepository.countForTicket(3)).thenReturn(0)
        `when`(adminRepository.totalBytesForTicket(3)).thenReturn(0)
        val adminProtection = SupportTicketRequestProtection(redis, properties, adminRepository)
        adminProtection.requireAttachmentQuota(99, SupportTicketMessageSender.ADMIN, 3, listOf(file))

        verify(adminRepository, never()).countForSender(99, SupportTicketMessageSender.ADMIN)
        verify(adminRepository, never()).totalBytesForSender(99, SupportTicketMessageSender.ADMIN)
    }

    @Test
    fun `rate limit exposes retry after returned by Redis`() {
        val protection = protection()
        `when`(
            redis.execute<Long>(
                any(),
                any<List<String>>(),
                any(),
                any(),
            ),
        ).thenReturn(17L)

        val error = assertFailsWith<SupportTicketRateLimitException> {
            protection.requireMessageRateAllowed(7, SupportTicketMessageSender.CUSTOMER, 3)
        }

        assertEquals(17, error.retryAfterSeconds)
        assertTrue(error.message.contains("发送工单消息"))
    }

    private fun protection(
        properties: SupportTicketProperties = SupportTicketProperties(),
    ) = SupportTicketRequestProtection(redis, properties, attachmentRepository)

    private companion object {
        const val EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}${'$'}EICAR-STANDARD-ANTIVIRUS-TEST-FILE!${'$'}H+H*"
    }
}
