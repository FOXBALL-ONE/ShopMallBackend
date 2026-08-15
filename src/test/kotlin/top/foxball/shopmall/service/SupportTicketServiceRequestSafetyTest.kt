package top.foxball.shopmall.service

import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockMultipartFile
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicket
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageAttachment
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.SupportTicketRequestInProgressException
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.SupportTicketMessageAttachmentRepository
import top.foxball.shopmall.repository.SupportTicketMessageRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.service.impl.SupportTicketServiceImpl
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SupportTicketServiceRequestSafetyTest {
    private val supportTicketRepository = mock(SupportTicketRepository::class.java)
    private val supportTicketMessageRepository = mock(SupportTicketMessageRepository::class.java)
    private val supportTicketMessageAttachmentRepository = mock(SupportTicketMessageAttachmentRepository::class.java)
    private val orderRepository = mock(OrderRepository::class.java)
    private val adminAccessService = mock(AdminAccessService::class.java)
    private val fileService = mock(FileService::class.java)
    private val requestProtection = mock(SupportTicketRequestProtection::class.java)
    private val idempotencyToken = SupportTicketRequestProtection.Token("key", "pending", "completed:")
    private val now = Instant.parse("2026-08-03T02:00:00Z")
    private val service = SupportTicketServiceImpl(
        supportTicketRepository,
        supportTicketMessageRepository,
        supportTicketMessageAttachmentRepository,
        orderRepository,
        adminAccessService,
        fileService,
        requestProtection,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @BeforeEach
    fun setUpRequestProtection() {
        `when`(requestProtection.normalizeIdempotencyKey(anyString())).thenAnswer { it.getArgument(0) }
        `when`(requestProtection.fingerprint(anyList(), anyList())).thenReturn("fingerprint")
        `when`(
            requestProtection.acquire(anyString(), anyLong(), anyString(), anyString()),
        ).thenReturn(SupportTicketRequestProtection.Acquisition.Acquired(idempotencyToken))
    }

    @Test
    fun `all customer service entry points reject an invalid account id before data access`() {
        assertFailsWith<ParamErrorException> {
            service.create(
                0,
                CreateSupportTicketCommand(
                    serviceType = SupportServiceType.PRE_SALES,
                    subject = "Question",
                    content = "Please help.",
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }
        assertFailsWith<ParamErrorException> {
            service.listCustomer(0, SupportTicketPageQuery())
        }
        assertFailsWith<ParamErrorException> {
            service.getCustomer(0, 3)
        }
        assertFailsWith<ParamErrorException> {
            service.sendCustomerMessage(
                0,
                3,
                SendSupportTicketMessageCommand(content = "Hello", idempotencyKey = IDEMPOTENCY_KEY),
            )
        }
        assertFailsWith<ParamErrorException> {
            service.closeByCustomer(0, 3)
        }

        verifyNoInteractions(
            supportTicketRepository,
            supportTicketMessageRepository,
            supportTicketMessageAttachmentRepository,
            orderRepository,
            adminAccessService,
            fileService,
            requestProtection,
        )
    }

    @Test
    fun `service rejects a subject longer than entity and controller limit`() {
        assertFailsWith<ParamErrorException> {
            service.create(
                7,
                CreateSupportTicketCommand(
                    serviceType = SupportServiceType.PRE_SALES,
                    subject = "x".repeat(121),
                    content = "Please help.",
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verifyNoInteractions(adminAccessService)
        verify(requestProtection, never()).normalizeIdempotencyKey(anyString())
        verifyNoInteractions(supportTicketRepository, orderRepository)
    }

    @Test
    fun `administrator can update status and priority while a blank reply is ignored`() {
        val ticket = ticket(SupportTicketStatus.OPEN).apply { adminReply = "Existing reply" }
        `when`(supportTicketRepository.findById(3)).thenReturn(Optional.of(ticket))
        `when`(supportTicketRepository.saveAndFlush(ticket)).thenReturn(ticket)

        val result = service.updateByAdmin(
            adminId = 99,
            ticketId = 3,
            command = UpdateSupportTicketCommand(
                status = SupportTicketStatus.RESOLVED,
                priority = SupportTicketPriority.HIGH,
                reply = "   ",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals(SupportTicketStatus.RESOLVED, result?.status)
        assertEquals(SupportTicketPriority.HIGH, result?.priority)
        assertEquals("Existing reply", result?.adminReply)
        assertEquals(99, result?.handledBy)
        verify(supportTicketMessageRepository, never()).saveAndFlush(any(SupportTicketMessage::class.java))
        verify(requestProtection, never()).requireMessageRateAllowed(
            99,
            SupportTicketMessageSender.ADMIN,
            3,
        )
    }

    @Test
    fun `administrator update rejects a blank reply when no other field is provided`() {
        assertFailsWith<ParamErrorException> {
            service.updateByAdmin(
                adminId = 99,
                ticketId = 3,
                command = UpdateSupportTicketCommand(
                    reply = "   ",
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verify(adminAccessService).requireAdmin(99)
        verifyNoInteractions(supportTicketRepository, supportTicketMessageRepository)
        verify(requestProtection, never()).normalizeIdempotencyKey(anyString())
    }

    @Test
    fun `completed create request replays stored ticket without rate limit or duplicate save`() {
        val ticket = SupportTicket(
            id = 12,
            customerId = 7,
            serviceType = SupportServiceType.PRE_SALES,
            subject = "Question",
            content = "Please help.",
            createdAt = now,
            updatedAt = now,
        )
        `when`(
            requestProtection.acquire(anyString(), anyLong(), anyString(), anyString()),
        ).thenReturn(SupportTicketRequestProtection.Acquisition.Completed(12))
        `when`(supportTicketRepository.findByIdAndCustomerId(12, 7)).thenReturn(ticket)

        val result = service.create(
            7,
            CreateSupportTicketCommand(
                serviceType = SupportServiceType.PRE_SALES,
                subject = "Question",
                content = "Please help.",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals(12, result.id)
        verify(requestProtection, never()).requireCreateRateAllowed(7)
        verify(requestProtection, never()).completeAfterCommit(idempotencyToken, 12)
        verify(supportTicketRepository, never()).saveAndFlush(any(SupportTicket::class.java))
        verifyNoInteractions(orderRepository)
    }

    @Test
    fun `pending create request fails without performing business side effects`() {
        `when`(
            requestProtection.acquire(anyString(), anyLong(), anyString(), anyString()),
        ).thenReturn(SupportTicketRequestProtection.Acquisition.Pending)

        assertFailsWith<SupportTicketRequestInProgressException> {
            service.create(
                7,
                CreateSupportTicketCommand(
                    serviceType = SupportServiceType.PRE_SALES,
                    subject = "Question",
                    content = "Please help.",
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verify(requestProtection, never()).requireCreateRateAllowed(7)
        verify(supportTicketRepository, never()).saveAndFlush(any(SupportTicket::class.java))
    }

    @Test
    fun `completed message request reissues attachment link without upload or duplicate save`() {
        val ticket = ticket(SupportTicketStatus.CLOSED)
        val multipart = MockMultipartFile("files", "evidence.txt", "text/plain", "evidence".toByteArray())
        val file = StoredFile(
            id = UUID.fromString("00000000-0000-0000-0000-000000000126"),
            ownerId = 7,
            originalFilename = "evidence.txt",
            storedFilename = "evidence.txt",
            relativePath = "2026/08/03/evidence.txt",
            contentType = "text/plain",
            sizeBytes = 8,
            sha256 = "e".repeat(64),
        )
        val message = SupportTicketMessage(
            id = 20,
            ticket = ticket,
            senderId = 7,
            senderType = SupportTicketMessageSender.CUSTOMER,
            content = null,
            createdAt = now,
        )
        val attachment = SupportTicketMessageAttachment(id = 26, message = message, file = file)
        val details = FileDetails(
            file = file,
            signedDownloadUrl = "https://files.example/api/files/${file.id}/download?renewed=true",
            downloadExpiresAt = LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC),
            scope = SUPPORT_TICKET_DOWNLOAD_SCOPE,
        )
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)
        `when`(
            requestProtection.acquire(anyString(), anyLong(), anyString(), anyString()),
        ).thenReturn(SupportTicketRequestProtection.Acquisition.Completed(20))
        `when`(supportTicketMessageRepository.findByIdAndTicket_Id(20, 3)).thenReturn(message)
        `when`(supportTicketMessageAttachmentRepository.findAllWithFileByMessageIds(listOf(20)))
            .thenReturn(listOf(attachment))
        `when`(fileService.createSupportTicketDownloadLinks(listOf(file))).thenReturn(listOf(details))

        val result = service.sendCustomerMessage(
            customerId = 7,
            ticketId = 3,
            command = SendSupportTicketMessageCommand(
                files = listOf(multipart),
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals(20, result?.id)
        assertEquals(details.signedDownloadUrl, result?.attachments?.single()?.signedDownloadUrl)
        verify(fileService).createSupportTicketDownloadLinks(listOf(file))
        verify(fileService, never()).upload(anyLong(), anyList())
        verify(requestProtection, never()).requireMessageRateAllowed(
            7,
            SupportTicketMessageSender.CUSTOMER,
            3,
        )
        verify(requestProtection, never()).requireAttachmentQuota(
            7,
            SupportTicketMessageSender.CUSTOMER,
            3,
            listOf(multipart),
        )
        verify(adminAccessService, never()).requireCustomerForUpdate(7)
        verify(requestProtection, never()).completeAfterCommit(idempotencyToken, 20)
        verify(supportTicketMessageRepository, never()).saveAndFlush(any(SupportTicketMessage::class.java))
        verify(supportTicketRepository, never()).saveAndFlush(ticket)
    }

    @Test
    fun `completed administrator update does not persist a second reply`() {
        val ticket = ticket(SupportTicketStatus.IN_PROGRESS).apply {
            adminReply = "Already saved"
            handledBy = 99
        }
        `when`(supportTicketRepository.findById(3)).thenReturn(Optional.of(ticket))
        `when`(
            requestProtection.acquire(anyString(), anyLong(), anyString(), anyString()),
        ).thenReturn(SupportTicketRequestProtection.Acquisition.Completed(3))

        val result = service.updateByAdmin(
            adminId = 99,
            ticketId = 3,
            command = UpdateSupportTicketCommand(
                reply = "Already saved",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals("Already saved", result?.adminReply)
        verify(supportTicketRepository, times(2)).findById(3)
        verify(supportTicketMessageRepository, never()).saveAndFlush(any(SupportTicketMessage::class.java))
        verify(supportTicketRepository, never()).saveAndFlush(ticket)
        verify(requestProtection, never()).requireMessageRateAllowed(
            99,
            SupportTicketMessageSender.ADMIN,
            3,
        )
        verify(requestProtection, never()).completeAfterCommit(idempotencyToken, 3)
    }

    @Test
    fun `message failure releases acquired idempotency reservation`() {
        val ticket = ticket(SupportTicketStatus.OPEN)
        val multipart = MockMultipartFile("files", "evidence.txt", "text/plain", "evidence".toByteArray())
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)
        doThrow(IllegalStateException("upload failed"))
            .`when`(fileService).upload(7, listOf(multipart))

        assertFailsWith<IllegalStateException> {
            service.sendCustomerMessage(
                customerId = 7,
                ticketId = 3,
                command = SendSupportTicketMessageCommand(
                    files = listOf(multipart),
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verify(requestProtection).release(idempotencyToken)
        verify(supportTicketMessageRepository, never()).saveAndFlush(any(SupportTicketMessage::class.java))
    }

    @Test
    fun `default detail page selects newest messages but returns page in chronological order`() {
        val ticket = ticket(SupportTicketStatus.OPEN)
        val newer = SupportTicketMessage(
            id = 11,
            ticket = ticket,
            senderId = 99,
            senderType = SupportTicketMessageSender.ADMIN,
            content = "Newer",
            createdAt = now,
        )
        val older = SupportTicketMessage(
            id = 10,
            ticket = ticket,
            senderId = 7,
            senderType = SupportTicketMessageSender.CUSTOMER,
            content = "Older",
            createdAt = now.minusSeconds(60),
        )
        val pageable = PageRequest.of(0, 50)
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)
        `when`(
            supportTicketMessageRepository.findAllByTicket_IdOrderByCreatedAtDescIdDesc(3, pageable),
        ).thenReturn(PageImpl(listOf(newer, older), pageable, 4))

        val result = service.getCustomer(7, 3)

        assertEquals(listOf(10L, 11L), result?.messages?.map { it.id })
        verify(supportTicketMessageRepository).findAllByTicket_IdOrderByCreatedAtDescIdDesc(3, pageable)
        verifyNoInteractions(fileService)
    }

    private fun ticket(status: SupportTicketStatus) = SupportTicket(
        id = 3,
        customerId = 7,
        serviceType = SupportServiceType.PRE_SALES,
        priority = SupportTicketPriority.MEDIUM,
        subject = "Question",
        content = "Please help.",
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    private companion object {
        const val IDEMPOTENCY_KEY = "ticket-request-001"
    }
}
