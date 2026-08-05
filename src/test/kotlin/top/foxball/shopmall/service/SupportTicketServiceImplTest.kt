package top.foxball.shopmall.service

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockMultipartFile
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicket
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageAttachment
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.SupportTicketStatusException
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
import kotlin.test.assertNull

class SupportTicketServiceImplTest {
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
    fun `creates pre sales ticket with low priority by default`() {
        `when`(supportTicketRepository.saveAndFlush(any(SupportTicket::class.java))).thenAnswer { invocation ->
            invocation.getArgument<SupportTicket>(0).apply {
                id = 1
                createdAt = now
                updatedAt = now
            }
        }

        val result = service.create(
            7,
            CreateSupportTicketCommand(
                serviceType = SupportServiceType.PRE_SALES,
                subject = "  Size advice  ",
                content = "  Which size should I choose?  ",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals(1, result.id)
        assertEquals(SupportTicketPriority.LOW, result.priority)
        assertEquals(SupportTicketStatus.OPEN, result.status)
        assertEquals("Size advice", result.subject)
        assertEquals("Which size should I choose?", result.content)
        assertNull(result.orderNo)
        verify(adminAccessService).requireCustomer(7)
        verifyNoInteractions(orderRepository)
    }

    @Test
    fun `after sales ticket requires an order number`() {
        assertFailsWith<ParamErrorException> {
            service.create(
                7,
                CreateSupportTicketCommand(
                    serviceType = SupportServiceType.AFTER_SALES,
                    subject = "Damaged item",
                    content = "The item arrived damaged.",
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verifyNoInteractions(orderRepository, supportTicketRepository)
    }

    @Test
    fun `after sales ticket links an order owned by current customer`() {
        val order = OrderEntity(id = 10, orderNo = "ORD-10", customerId = 7)
        `when`(orderRepository.findByOrderNoAndCustomerId("ORD-10", 7)).thenReturn(order)
        `when`(supportTicketRepository.saveAndFlush(any(SupportTicket::class.java))).thenAnswer { invocation ->
            invocation.getArgument<SupportTicket>(0).apply { id = 2 }
        }

        val result = service.create(
            7,
            CreateSupportTicketCommand(
                serviceType = SupportServiceType.AFTER_SALES,
                priority = SupportTicketPriority.HIGH,
                orderNo = " ORD-10 ",
                subject = "Damaged item",
                content = "The item arrived damaged.",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals("ORD-10", result.orderNo)
        assertEquals(SupportTicketPriority.HIGH, result.priority)
        verify(orderRepository).findByOrderNoAndCustomerId("ORD-10", 7)
    }

    @Test
    fun `after sales ticket rejects missing or foreign order`() {
        `when`(orderRepository.findByOrderNoAndCustomerId("ORD-99", 7)).thenReturn(null)

        assertFailsWith<OrderNotFoundException> {
            service.create(
                7,
                CreateSupportTicketCommand(
                    serviceType = SupportServiceType.AFTER_SALES,
                    orderNo = "ORD-99",
                    subject = "Order issue",
                    content = "Please help.",
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verify(supportTicketRepository, never()).saveAndFlush(any(SupportTicket::class.java))
    }

    @Test
    fun `customer closes only a ticket returned for that customer`() {
        val ticket = ticket(status = SupportTicketStatus.IN_PROGRESS)
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)
        `when`(supportTicketRepository.saveAndFlush(ticket)).thenReturn(ticket)

        val result = service.closeByCustomer(7, 3)

        assertEquals(SupportTicketStatus.CLOSED, result?.status)
        assertEquals(now, result?.closedAt)
        verify(adminAccessService).requireCustomer(7)
        verify(supportTicketRepository).findByIdAndCustomerId(3, 7)
        verify(supportTicketRepository).saveAndFlush(ticket)
    }

    @Test
    fun `customer cannot see another customers ticket`() {
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(null)

        assertNull(service.getCustomer(7, 3))
        verify(adminAccessService).requireCustomer(7)
    }

    @Test
    fun `admin can resolve prioritize and reply to a ticket`() {
        val ticket = ticket(status = SupportTicketStatus.OPEN)
        `when`(supportTicketRepository.findById(3)).thenReturn(Optional.of(ticket))
        `when`(supportTicketMessageRepository.saveAndFlush(any(SupportTicketMessage::class.java))).thenAnswer {
            it.getArgument<SupportTicketMessage>(0).apply {
                id = 16
                createdAt = now
            }
        }
        `when`(supportTicketRepository.saveAndFlush(ticket)).thenReturn(ticket)

        val result = service.updateByAdmin(
            adminId = 99,
            ticketId = 3,
            command = UpdateSupportTicketCommand(
                status = SupportTicketStatus.RESOLVED,
                priority = SupportTicketPriority.HIGH,
                reply = "  A replacement has been arranged.  ",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        verify(adminAccessService).requireAdmin(99)
        assertEquals(SupportTicketStatus.RESOLVED, result?.status)
        assertEquals(SupportTicketPriority.HIGH, result?.priority)
        assertEquals("A replacement has been arranged.", result?.adminReply)
        assertEquals(99, result?.handledBy)
        assertEquals(now, result?.repliedAt)
        assertEquals(now, result?.resolvedAt)
        verify(supportTicketMessageRepository).saveAndFlush(any(SupportTicketMessage::class.java))
        verify(supportTicketRepository, times(1)).saveAndFlush(ticket)
    }

    @Test
    fun `closed ticket cannot be reopened by admin`() {
        val ticket = ticket(status = SupportTicketStatus.CLOSED).apply { closedAt = now }
        `when`(supportTicketRepository.findById(3)).thenReturn(Optional.of(ticket))

        assertFailsWith<SupportTicketStatusException> {
            service.updateByAdmin(
                adminId = 99,
                ticketId = 3,
                command = UpdateSupportTicketCommand(
                    status = SupportTicketStatus.IN_PROGRESS,
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verify(supportTicketRepository, never()).saveAndFlush(ticket)
    }

    @Test
    fun `closed ticket rejects legacy administrator reply`() {
        val ticket = ticket(status = SupportTicketStatus.CLOSED).apply { closedAt = now }
        `when`(supportTicketRepository.findById(3)).thenReturn(Optional.of(ticket))

        assertFailsWith<SupportTicketStatusException> {
            service.updateByAdmin(
                adminId = 99,
                ticketId = 3,
                command = UpdateSupportTicketCommand(
                    reply = "This reply is too late.",
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

        verify(supportTicketMessageRepository, never()).saveAndFlush(any(SupportTicketMessage::class.java))
        verify(supportTicketRepository, never()).saveAndFlush(ticket)
    }

    @Test
    fun `customer can send an attachment only message to an open ticket`() {
        val ticket = ticket(status = SupportTicketStatus.OPEN)
        val file = StoredFile(
            id = UUID.fromString("00000000-0000-0000-0000-000000000123"),
            ownerId = 7,
            originalFilename = "damage.jpg",
            storedFilename = "damage.jpg",
            relativePath = "2026/08/03/damage.jpg",
            contentType = "image/jpeg",
            sizeBytes = 123L,
            sha256 = "a".repeat(64),
        )
        val fileDetails = FileDetails(
            file = file,
            signedDownloadUrl = "https://files.example/api/files/${file.id}/download",
            downloadExpiresAt = LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC),
            scope = SUPPORT_TICKET_DOWNLOAD_SCOPE,
        )
        val multipart = MockMultipartFile("files", "damage.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)
        `when`(fileService.upload(7, listOf(multipart))).thenReturn(listOf(fileDetails))
        `when`(fileService.createSupportTicketDownloadLinks(listOf(file))).thenReturn(listOf(fileDetails))
        `when`(supportTicketMessageRepository.saveAndFlush(any(SupportTicketMessage::class.java))).thenAnswer {
            it.getArgument<SupportTicketMessage>(0).apply {
                id = 17
                createdAt = now
                attachments.single().id = 23
            }
        }
        `when`(supportTicketRepository.saveAndFlush(ticket)).thenReturn(ticket)

        val result = service.sendCustomerMessage(
            customerId = 7,
            ticketId = 3,
            command = SendSupportTicketMessageCommand(
                files = listOf(multipart),
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals(17, result?.id)
        assertEquals(SupportTicketMessageSender.CUSTOMER, result?.senderType)
        assertNull(result?.content)
        assertEquals(1, result?.attachments?.size)
        assertEquals(file.id, result?.attachments?.single()?.fileId)
        assertEquals(fileDetails.signedDownloadUrl, result?.attachments?.single()?.signedDownloadUrl)
        verify(requestProtection).requireMessageRateAllowed(7, SupportTicketMessageSender.CUSTOMER, 3)
        verify(requestProtection).requireAttachmentQuota(7, SupportTicketMessageSender.CUSTOMER, 3, listOf(multipart))
        verify(adminAccessService).requireCustomerForUpdate(7)
        verify(fileService).upload(7, listOf(multipart))
        verify(supportTicketMessageRepository).saveAndFlush(any(SupportTicketMessage::class.java))
    }

    @Test
    fun `closed ticket rejects a message before files are uploaded`() {
        val ticket = ticket(status = SupportTicketStatus.CLOSED)
        val multipart = MockMultipartFile("files", "damage.jpg", "image/jpeg", byteArrayOf(1))
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)

        assertFailsWith<SupportTicketStatusException> {
            service.sendCustomerMessage(
                customerId = 7,
                ticketId = 3,
                command = SendSupportTicketMessageCommand(
                    files = listOf(multipart),
                    idempotencyKey = IDEMPOTENCY_KEY,
                ),
            )
        }

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
        verifyNoInteractions(fileService)
    }

    @Test
    fun `message requires either text or an attachment`() {
        val ticket = ticket(status = SupportTicketStatus.OPEN)
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)

        assertFailsWith<ParamErrorException> {
            service.sendCustomerMessage(
                customerId = 7,
                ticketId = 3,
                command = SendSupportTicketMessageCommand(content = "   ", idempotencyKey = IDEMPOTENCY_KEY),
            )
        }

        verifyNoInteractions(fileService, supportTicketMessageRepository)
    }

    @Test
    fun `administrator message assigns handler and moves an open ticket into progress`() {
        val ticket = ticket(status = SupportTicketStatus.OPEN)
        `when`(supportTicketRepository.findById(3)).thenReturn(Optional.of(ticket))
        `when`(supportTicketMessageRepository.saveAndFlush(any(SupportTicketMessage::class.java))).thenAnswer {
            it.getArgument<SupportTicketMessage>(0).apply {
                id = 18
                createdAt = now
            }
        }
        `when`(supportTicketRepository.saveAndFlush(ticket)).thenReturn(ticket)

        val result = service.sendAdminMessage(
            adminId = 99,
            ticketId = 3,
            command = SendSupportTicketMessageCommand(
                content = "We are checking this for you.",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals(18, result?.id)
        assertEquals(SupportTicketMessageSender.ADMIN, result?.senderType)
        assertEquals(SupportTicketStatus.IN_PROGRESS, ticket.status)
        assertEquals(99, ticket.handledBy)
        assertEquals("We are checking this for you.", ticket.adminReply)
        verify(adminAccessService).requireAdmin(99)
        verifyNoInteractions(fileService)
    }

    @Test
    fun `administrator attachment message preserves the latest textual reply`() {
        val ticket = ticket(status = SupportTicketStatus.IN_PROGRESS).apply {
            adminReply = "The replacement is ready."
        }
        val file = StoredFile(
            id = UUID.fromString("00000000-0000-0000-0000-000000000124"),
            ownerId = 99,
            originalFilename = "replacement.pdf",
            storedFilename = "replacement.pdf",
            relativePath = "2026/08/03/replacement.pdf",
            contentType = "application/pdf",
            sizeBytes = 456L,
            sha256 = "c".repeat(64),
        )
        val fileDetails = FileDetails(
            file = file,
            signedDownloadUrl = "https://files.example/api/files/${file.id}/download",
            downloadExpiresAt = LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC),
            scope = SUPPORT_TICKET_DOWNLOAD_SCOPE,
        )
        val multipart = MockMultipartFile("files", "replacement.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        `when`(supportTicketRepository.findById(3)).thenReturn(Optional.of(ticket))
        `when`(fileService.upload(99, listOf(multipart))).thenReturn(listOf(fileDetails))
        `when`(fileService.createSupportTicketDownloadLinks(listOf(file))).thenReturn(listOf(fileDetails))
        `when`(supportTicketMessageRepository.saveAndFlush(any(SupportTicketMessage::class.java))).thenAnswer {
            it.getArgument<SupportTicketMessage>(0).apply {
                id = 20
                createdAt = now
                attachments.single().id = 24
            }
        }
        `when`(supportTicketRepository.saveAndFlush(ticket)).thenReturn(ticket)

        service.sendAdminMessage(
            adminId = 99,
            ticketId = 3,
            command = SendSupportTicketMessageCommand(
                files = listOf(multipart),
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals("The replacement is ready.", ticket.adminReply)
        assertEquals(now, ticket.repliedAt)
    }

    @Test
    fun `ticket detail pages messages and signs all page attachments in one batch`() {
        val ticket = ticket(status = SupportTicketStatus.OPEN)
        val message = SupportTicketMessage(
            id = 21,
            ticket = ticket,
            senderId = 7,
            senderType = SupportTicketMessageSender.CUSTOMER,
            content = "Evidence attached.",
            createdAt = now,
        )
        val file = StoredFile(
            id = UUID.fromString("00000000-0000-0000-0000-000000000125"),
            ownerId = 7,
            originalFilename = "evidence.txt",
            storedFilename = "evidence.txt",
            relativePath = "2026/08/03/evidence.txt",
            contentType = "text/plain",
            sizeBytes = 12L,
            sha256 = "d".repeat(64),
        )
        val attachment = SupportTicketMessageAttachment(id = 25, message = message, file = file)
        val fileDetails = FileDetails(
            file = file,
            signedDownloadUrl = "https://files.example/api/files/${file.id}/download",
            downloadExpiresAt = LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC),
            scope = SUPPORT_TICKET_DOWNLOAD_SCOPE,
        )
        val pageable = PageRequest.of(1, 2)
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)
        `when`(
            supportTicketMessageRepository.findAllByTicket_IdOrderByCreatedAtDescIdDesc(3, pageable),
        ).thenReturn(PageImpl(listOf(message), pageable, 5))
        `when`(supportTicketMessageAttachmentRepository.findAllWithFileByMessageIds(listOf(21)))
            .thenReturn(listOf(attachment))
        `when`(fileService.createSupportTicketDownloadLinks(listOf(file))).thenReturn(listOf(fileDetails))

        val result = service.getCustomer(7, 3, messagePage = 1, messageSize = 2)

        assertEquals(3, result?.messageTotalPages)
        assertEquals(5, result?.messageTotalElements)
        assertEquals("evidence.txt", result?.messages?.single()?.attachments?.single()?.fileName)
        verify(fileService).createSupportTicketDownloadLinks(listOf(file))
    }

    @Test
    fun `customer follow up reopens a resolved ticket`() {
        val ticket = ticket(status = SupportTicketStatus.RESOLVED).apply { resolvedAt = now.minusSeconds(60) }
        `when`(supportTicketRepository.findByIdAndCustomerId(3, 7)).thenReturn(ticket)
        `when`(supportTicketMessageRepository.saveAndFlush(any(SupportTicketMessage::class.java))).thenAnswer {
            it.getArgument<SupportTicketMessage>(0).apply {
                id = 19
                createdAt = now
            }
        }
        `when`(supportTicketRepository.saveAndFlush(ticket)).thenReturn(ticket)

        service.sendCustomerMessage(
            customerId = 7,
            ticketId = 3,
            command = SendSupportTicketMessageCommand(
                content = "The problem is still present.",
                idempotencyKey = IDEMPOTENCY_KEY,
            ),
        )

        assertEquals(SupportTicketStatus.OPEN, ticket.status)
        assertNull(ticket.resolvedAt)
        verify(adminAccessService, never()).requireCustomerForUpdate(7)
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "ticket-request-001"
    }

    private fun ticket(status: SupportTicketStatus) = SupportTicket(
        id = 3,
        customerId = 7,
        serviceType = SupportServiceType.AFTER_SALES,
        priority = SupportTicketPriority.MEDIUM,
        order = OrderEntity(id = 10, orderNo = "ORD-10", customerId = 7),
        subject = "Damaged item",
        content = "The item arrived damaged.",
        status = status,
        createdAt = now,
        updatedAt = now,
    )
}
