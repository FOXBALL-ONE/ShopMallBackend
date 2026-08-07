package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.http.MediaType
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.SupportTicketRateLimitException
import top.foxball.shopmall.controller.admin.AdminSupportTicketController
import top.foxball.shopmall.service.CreateSupportTicketCommand
import top.foxball.shopmall.service.SendSupportTicketMessageCommand
import top.foxball.shopmall.service.SupportTicketAttachmentView
import top.foxball.shopmall.service.SupportTicketMessageView
import top.foxball.shopmall.service.SupportTicketService
import top.foxball.shopmall.service.SupportTicketView
import top.foxball.shopmall.service.UpdateSupportTicketCommand
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class SupportTicketControllerTest {
    private lateinit var supportTicketService: SupportTicketService
    private lateinit var userService: UserService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        supportTicketService = mock(SupportTicketService::class.java)
        userService = mock(UserService::class.java)
        val customer = User(id = 7, username = "customer")
        `when`(userService.getUsernameById(7)).thenReturn("customer")
        `when`(userService.getUsernameById(99)).thenReturn("admin")
        `when`(userService.getUserByUsername("customer")).thenReturn(customer)
        `when`(userService.getUsernamesByIds(anyList())).thenReturn(mapOf(7L to "customer", 99L to "admin"))
        mockMvc = MockMvcBuilders.standaloneSetup(
            SupportTicketController(supportTicketService, ResponseBuilder()),
            AdminSupportTicketController(supportTicketService, userService, ResponseBuilder()),
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `ticket options expose service and priority choices with low default`() {
        mockMvc.perform(get("/api/support-tickets/options"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.service_types[0].value").value("PRE_SALES"))
            .andExpect(jsonPath("$.data.service_types[0].label").value("售前咨询"))
            .andExpect(jsonPath("$.data.service_types[1].value").value("AFTER_SALES"))
            .andExpect(jsonPath("$.data.service_types[1].label").value("售后支持"))
            .andExpect(jsonPath("$.data.priorities[0].value").value("LOW"))
            .andExpect(jsonPath("$.data.priorities[2].value").value("HIGH"))
            .andExpect(jsonPath("$.data.default_priority").value("LOW"))

        verifyNoInteractions(supportTicketService)
    }

    @Test
    fun `create ticket defaults priority to low and uses snake case response fields`() {
        authenticate(7)
        val command = CreateSupportTicketCommand(
            serviceType = SupportServiceType.PRE_SALES,
            priority = SupportTicketPriority.LOW,
            orderNo = null,
            subject = "Size advice",
            content = "Which size should I choose?",
            idempotencyKey = IDEMPOTENCY_KEY,
        )
        `when`(supportTicketService.create(7, command)).thenReturn(ticket())

        mockMvc.perform(
            post("/api/support-tickets")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .param("service_type", "PRE_SALES")
                .param("subject", "Size advice")
                .param("content", "Which size should I choose?"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.customer_id").value(7))
            .andExpect(jsonPath("$.data.service_type").value("PRE_SALES"))
            .andExpect(jsonPath("$.data.priority").value("LOW"))
            .andExpect(jsonPath("$.data.order_no").doesNotExist())
            .andExpect(jsonPath("$.data.status").value("OPEN"))

        verify(supportTicketService).create(7, command)
    }

    @Test
    fun `ticket rate limit response includes retry after header`() {
        authenticate(7)
        val command = CreateSupportTicketCommand(
            serviceType = SupportServiceType.PRE_SALES,
            priority = SupportTicketPriority.LOW,
            orderNo = null,
            subject = "Size advice",
            content = "Which size should I choose?",
            idempotencyKey = IDEMPOTENCY_KEY,
        )
        `when`(supportTicketService.create(7, command))
            .thenThrow(SupportTicketRateLimitException(17, "创建工单过于频繁，请稍后再试"))

        mockMvc.perform(
            post("/api/support-tickets")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .param("service_type", "PRE_SALES")
                .param("subject", "Size advice")
                .param("content", "Which size should I choose?"),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "17"))
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.message").value("创建工单过于频繁，请稍后再试"))
    }

    @Test
    fun `create after sales ticket forwards selected order and priority`() {
        authenticate(7)
        val command = CreateSupportTicketCommand(
            serviceType = SupportServiceType.AFTER_SALES,
            priority = SupportTicketPriority.HIGH,
            orderNo = "ORD-10",
            subject = "Damaged item",
            content = "The item arrived damaged.",
            idempotencyKey = IDEMPOTENCY_KEY,
        )
        `when`(supportTicketService.create(7, command)).thenReturn(
            ticket(
                serviceType = SupportServiceType.AFTER_SALES,
                priority = SupportTicketPriority.HIGH,
                orderNo = "ORD-10",
            ),
        )

        mockMvc.perform(
            post("/api/support-tickets")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .param("service_type", "AFTER_SALES")
                .param("priority", "HIGH")
                .param("order_no", "ORD-10")
                .param("subject", "Damaged item")
                .param("content", "The item arrived damaged."),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.order_no").value("ORD-10"))
            .andExpect(jsonPath("$.data.priority").value("HIGH"))

        verify(supportTicketService).create(7, command)
    }

    @Test
    fun `missing ticket content is rejected before service call`() {
        authenticate(7)

        mockMvc.perform(
            post("/api/support-tickets")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .param("service_type", "PRE_SALES")
                .param("subject", "Size advice"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))

        verifyNoInteractions(supportTicketService)
    }

    @Test
    fun `ticket subject longer than 120 characters is rejected`() {
        authenticate(7)
        val command = CreateSupportTicketCommand(
            serviceType = SupportServiceType.PRE_SALES,
            subject = "s".repeat(121),
            content = "Question",
            idempotencyKey = IDEMPOTENCY_KEY,
        )
        `when`(supportTicketService.create(7, command))
            .thenThrow(ParamErrorException("工单主题不能超过 120 个字符"))

        mockMvc.perform(
            post("/api/support-tickets")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .param("service_type", "PRE_SALES")
                .param("subject", "s".repeat(121))
                .param("content", "Question"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("工单主题不能超过 120 个字符"))

        verify(supportTicketService).create(7, command)
    }

    @Test
    fun `customer ticket detail is scoped by authenticated user`() {
        authenticate(7)
        `when`(supportTicketService.getCustomer(7, 99)).thenReturn(null)

        mockMvc.perform(get("/api/support-tickets/99"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").value("工单不存在"))

        verify(supportTicketService).getCustomer(7, 99)
    }

    @Test
    fun `customer ticket detail forwards message pagination and returns its totals`() {
        authenticate(7)
        `when`(supportTicketService.getCustomer(7, 3, 1, 10)).thenReturn(
            ticket().copy(
                messages = listOf(messageView("Paged message")),
                messageTotalPages = 4,
                messageTotalElements = 31,
            ),
        )

        mockMvc.perform(
            get("/api/support-tickets/3")
                .param("message_page", "2")
                .param("message_size", "10"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.messages[0].content").value("Paged message"))
            .andExpect(jsonPath("$.data.message_pagination.count").value(4))
            .andExpect(jsonPath("$.data.message_pagination.total").value(31))
            .andExpect(jsonPath("$.data.handled_by").doesNotExist())
            .andExpect(jsonPath("$.data.messages[0].sender_id").doesNotExist())

        verify(supportTicketService).getCustomer(7, 3, 1, 10)
    }

    @Test
    fun `optimistic ticket conflict returns http conflict`() {
        authenticate(7)
        `when`(supportTicketService.getCustomer(7, 3)).thenThrow(
            ObjectOptimisticLockingFailureException("SupportTicket", 3L),
        )

        mockMvc.perform(get("/api/support-tickets/3"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("数据已被其他操作更新，请刷新后重试"))
    }

    @Test
    fun `customer can post a multipart message with an attachment`() {
        authenticate(7)
        val file = MockMultipartFile("files", "damage.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val content = MockPart("content", "Photo attached".toByteArray()).apply {
            headers.contentType = MediaType.TEXT_PLAIN
        }
        `when`(
            supportTicketService.sendCustomerMessage(
                anyLong(),
                anyLong(),
                anyMessageCommand(),
            ),
        ).thenAnswer { invocation ->
            val command = invocation.getArgument<SendSupportTicketMessageCommand>(2)
            kotlin.test.assertEquals("Photo attached", command.content)
            kotlin.test.assertEquals("damage.jpg", command.files.single().originalFilename)
            kotlin.test.assertEquals(IDEMPOTENCY_KEY, command.idempotencyKey)
            messageView(command.content)
        }

        mockMvc.perform(
            multipart("/api/support-tickets/3/messages")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .file(file)
                .part(content),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.ticket_id").value(3))
            .andExpect(jsonPath("$.data.sender_type").value("CUSTOMER"))
            .andExpect(jsonPath("$.data.content").value("Photo attached"))
            .andExpect(jsonPath("$.data.attachments[0].file_name").value("damage.jpg"))
            .andExpect(jsonPath("$.data.attachments[0].download_url").value("https://files.example/download"))
            .andExpect(jsonPath("$.data.sender_id").doesNotExist())
    }

    @Test
    fun `administrator can post an attachment message`() {
        authenticate(99)
        val file = MockMultipartFile("files", "replacement.pdf", "application/pdf", byteArrayOf(4, 5, 6))
        `when`(
            supportTicketService.sendAdminMessage(
                anyLong(),
                anyLong(),
                anyMessageCommand(),
            ),
        ).thenAnswer { invocation ->
            val command = invocation.getArgument<SendSupportTicketMessageCommand>(2)
            kotlin.test.assertEquals("replacement.pdf", command.files.single().originalFilename)
            kotlin.test.assertEquals(IDEMPOTENCY_KEY, command.idempotencyKey)
            messageView(senderId = 99, senderType = SupportTicketMessageSender.ADMIN)
        }

        mockMvc.perform(
            multipart("/admin/api/support-tickets/3/messages")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .file(file),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.ticket_id").value(3))
            .andExpect(jsonPath("$.data.sender_id").value(99))
            .andExpect(jsonPath("$.data.sender_username").value("admin"))
            .andExpect(jsonPath("$.data.sender_type").value("ADMIN"))
    }

    @Test
    fun `admin update forwards status priority and reply`() {
        authenticate(99)
        val command = UpdateSupportTicketCommand(
            status = SupportTicketStatus.RESOLVED,
            priority = SupportTicketPriority.HIGH,
            reply = "Replacement arranged.",
            idempotencyKey = IDEMPOTENCY_KEY,
        )
        `when`(supportTicketService.updateByAdmin(99, 3, command)).thenReturn(
            ticket(
                serviceType = SupportServiceType.AFTER_SALES,
                priority = SupportTicketPriority.HIGH,
                orderNo = "ORD-10",
                status = SupportTicketStatus.RESOLVED,
                adminReply = "Replacement arranged.",
                handledBy = 99,
            ),
        )

        mockMvc.perform(
            put("/admin/api/support-tickets/3")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .param("status", "RESOLVED")
                .param("priority", "HIGH")
                .param("reply", "Replacement arranged."),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("RESOLVED"))
            .andExpect(jsonPath("$.data.admin_reply").value("Replacement arranged."))
            .andExpect(jsonPath("$.data.handled_by").value(99))
            .andExpect(jsonPath("$.data.customer_username").value("customer"))
            .andExpect(jsonPath("$.data.handled_by_username").value("admin"))

        verify(supportTicketService).updateByAdmin(99, 3, command)
    }

    private fun authenticate(userId: Long) {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = TestingAuthenticationToken(userId, null)
        SecurityContextHolder.setContext(context)
    }

    private fun anyMessageCommand(): SendSupportTicketMessageCommand =
        any(SendSupportTicketMessageCommand::class.java)
            ?: SendSupportTicketMessageCommand(idempotencyKey = IDEMPOTENCY_KEY)

    private fun messageView(
        content: String? = null,
        senderId: Long = 7,
        senderType: SupportTicketMessageSender = SupportTicketMessageSender.CUSTOMER,
    ) = SupportTicketMessageView(
        id = 21,
        senderId = senderId,
        senderType = senderType,
        content = content,
        attachments = listOf(
            SupportTicketAttachmentView(
                id = 31,
                fileId = UUID.fromString("00000000-0000-0000-0000-000000000123"),
                fileName = "damage.jpg",
                contentType = "image/jpeg",
                sizeBytes = 3,
                signedDownloadUrl = "https://files.example/download",
                downloadExpiresAt = LocalDateTime.parse("2026-08-03T02:05:00"),
            ),
        ),
        createdAt = Instant.parse("2026-08-03T02:00:00Z"),
    )

    private fun ticket(
        serviceType: SupportServiceType = SupportServiceType.PRE_SALES,
        priority: SupportTicketPriority = SupportTicketPriority.LOW,
        orderNo: String? = null,
        status: SupportTicketStatus = SupportTicketStatus.OPEN,
        adminReply: String? = null,
        handledBy: Long? = null,
    ) = SupportTicketView(
        id = 3,
        customerId = 7,
        serviceType = serviceType,
        priority = priority,
        orderNo = orderNo,
        subject = "Size advice",
        content = "Which size should I choose?",
        status = status,
        adminReply = adminReply,
        handledBy = handledBy,
        repliedAt = null,
        resolvedAt = null,
        closedAt = null,
        createdAt = Instant.parse("2026-08-03T02:00:00Z"),
        updatedAt = Instant.parse("2026-08-03T02:00:00Z"),
    )

    private companion object {
        const val IDEMPOTENCY_KEY = "ticket-request-001"
    }
}

