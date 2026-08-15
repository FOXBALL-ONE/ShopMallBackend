package top.foxball.shopmall.service.impl

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicket
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageAttachment
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.handler.IdempotencyConflictException
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.SupportTicketRequestInProgressException
import top.foxball.shopmall.handler.SupportTicketStatusException
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.SupportTicketMessageAttachmentRepository
import top.foxball.shopmall.repository.SupportTicketMessageRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminSupportTicketQuery
import top.foxball.shopmall.service.CreateSupportTicketCommand
import top.foxball.shopmall.service.FileDetails
import top.foxball.shopmall.service.FileService
import top.foxball.shopmall.service.SendSupportTicketMessageCommand
import top.foxball.shopmall.service.SupportTicketAttachmentView
import top.foxball.shopmall.service.SupportTicketMessageView
import top.foxball.shopmall.service.SupportTicketPageQuery
import top.foxball.shopmall.service.SupportTicketRequestProtection
import top.foxball.shopmall.service.SupportTicketService
import top.foxball.shopmall.service.SupportTicketView
import top.foxball.shopmall.service.UpdateSupportTicketCommand
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** [SupportTicketService] 的事务实现。 */
@Service
@Transactional(readOnly = true)
class SupportTicketServiceImpl(
    private val supportTicketRepository: SupportTicketRepository,
    private val supportTicketMessageRepository: SupportTicketMessageRepository,
    private val supportTicketMessageAttachmentRepository: SupportTicketMessageAttachmentRepository,
    private val orderRepository: OrderRepository,
    private val adminAccessService: AdminAccessService,
    private val fileService: FileService,
    private val requestProtection: SupportTicketRequestProtection,
    private val clock: Clock,
) : SupportTicketService {
    @Transactional
    override fun create(customerId: Long, command: CreateSupportTicketCommand): SupportTicketView {
        requireAuthenticatedAccountId(customerId)

        val subject = normalizeRequiredText(command.subject, SUBJECT_MAX_LENGTH, "工单主题")
        val content = normalizeRequiredText(command.content, CONTENT_MAX_LENGTH, "工单内容")
        val orderNo = normalizeOrderNo(command.orderNo)
        if (command.serviceType == SupportServiceType.AFTER_SALES && orderNo == null) {
            throw ParamErrorException("售后支持必须选择订单")
        }
        val idempotencyKey = requestProtection.normalizeIdempotencyKey(command.idempotencyKey)
        val fingerprint = requestProtection.fingerprint(
            listOf(command.serviceType.name, command.priority.name, orderNo, subject, content),
        )

        return when (
            val acquisition = requestProtection.acquire(
                operation = "create",
                actorId = customerId,
                clientKey = idempotencyKey,
                fingerprint = fingerprint,
            )
        ) {
            is SupportTicketRequestProtection.Acquisition.Completed ->
                supportTicketRepository.findByIdAndCustomerId(acquisition.resultId, customerId)?.toView()
                    ?: throw IdempotencyConflictException("工单幂等结果不存在，请更换 Idempotency-Key")

            SupportTicketRequestProtection.Acquisition.Pending -> throw SupportTicketRequestInProgressException()
            is SupportTicketRequestProtection.Acquisition.Acquired -> {
                try {
                    requestProtection.requireCreateRateAllowed(customerId)
                    val order = orderNo?.let {
                        orderRepository.findByOrderNoAndCustomerId(it, customerId)
                            ?: orderRepository.findByOrderNo(it)?.also { order ->
                                if (order.customerId != customerId) {
                                    throw ForbiddenException("只能关联自己的订单")
                                }
                            }
                            ?: throw OrderNotFoundException("订单不存在")
                    }
                    val ticket = supportTicketRepository.saveAndFlush(
                        SupportTicket(
                            customerId = customerId,
                            serviceType = command.serviceType,
                            priority = command.priority,
                            order = order,
                            subject = subject,
                            content = content,
                        ),
                    )
                    val ticketId = requireNotNull(ticket.id)
                    requestProtection.completeAfterCommit(acquisition.token, ticketId)
                    ticket.toView()
                } catch (ex: Exception) {
                    releaseAfterFailure(acquisition.token, ex)
                    throw ex
                }
            }
        }
    }

    override fun listCustomer(customerId: Long, query: SupportTicketPageQuery): Page<SupportTicketView> {
        requireAuthenticatedAccountId(customerId)
        val pageable = pageRequest(query.page, query.size)
        return supportTicketRepository.findAllForCustomer(
            customerId = customerId,
            status = query.status,
            serviceType = query.serviceType,
            priority = query.priority,
            pageable = pageable,
        ).map { it.toView() }
    }

    override fun getCustomer(
        customerId: Long,
        ticketId: Long,
        messagePage: Int,
        messageSize: Int,
    ): SupportTicketView? {
        requireAuthenticatedAccountId(customerId)
        val ticket = customerTicket(ticketId, customerId) ?: return null
        val messages = messagesFor(ticketId, messagePage, messageSize)
        return ticket.toView(messages.content, messages.totalPages, messages.totalElements)
    }

    @Transactional
    override fun sendCustomerMessage(
        customerId: Long,
        ticketId: Long,
        command: SendSupportTicketMessageCommand,
    ): SupportTicketMessageView? {
        requireAuthenticatedAccountId(customerId)
        val ticket = customerTicket(ticketId, customerId) ?: return null
        return sendMessage(ticket, customerId, SupportTicketMessageSender.CUSTOMER, command)
    }

    @Transactional
    override fun closeByCustomer(customerId: Long, ticketId: Long): SupportTicketView? {
        requireAuthenticatedAccountId(customerId)
        val ticket = customerTicket(ticketId, customerId) ?: return null
        if (ticket.status != SupportTicketStatus.CLOSED) {
            ticket.status = SupportTicketStatus.CLOSED
            ticket.closedAt = Instant.now(clock)
            supportTicketRepository.saveAndFlush(ticket)
        }
        return ticket.toView()
    }

    override fun listAdmin(adminId: Long, query: AdminSupportTicketQuery): Page<SupportTicketView> {
        adminAccessService.requireAdmin(adminId)
        val pageable = pageRequest(query.page, query.size)
        val orderNo = normalizeOrderNo(query.orderNo)
        return supportTicketRepository.findAllForAdmin(
            status = query.status,
            serviceType = query.serviceType,
            priority = query.priority,
            customerId = query.customerId,
            orderNo = orderNo,
            pageable = pageable,
        ).map { it.toView() }
    }

    override fun getAdmin(
        adminId: Long,
        ticketId: Long,
        messagePage: Int,
        messageSize: Int,
    ): SupportTicketView? {
        adminAccessService.requireAdmin(adminId)
        val ticket = supportTicketRepository.findById(ticketId).orElse(null) ?: return null
        val messages = messagesFor(ticketId, messagePage, messageSize)
        return ticket.toView(messages.content, messages.totalPages, messages.totalElements)
    }

    @Transactional
    override fun sendAdminMessage(
        adminId: Long,
        ticketId: Long,
        command: SendSupportTicketMessageCommand,
    ): SupportTicketMessageView? {
        adminAccessService.requireAdmin(adminId)
        val ticket = supportTicketRepository.findById(ticketId).orElse(null) ?: return null
        return sendMessage(ticket, adminId, SupportTicketMessageSender.ADMIN, command)
    }

    @Transactional
    override fun updateByAdmin(
        adminId: Long,
        ticketId: Long,
        command: UpdateSupportTicketCommand,
    ): SupportTicketView? {
        adminAccessService.requireAdmin(adminId)
        val reply = normalizeOptionalMessageContent(command.reply)
        if (command.status == null && command.priority == null && reply == null) {
            throw ParamErrorException("至少提供一个要更新的字段")
        }
        val ticket = supportTicketRepository.findById(ticketId).orElse(null) ?: return null
        val idempotencyKey = requestProtection.normalizeIdempotencyKey(command.idempotencyKey)
        val fingerprint = requestProtection.fingerprint(
            listOf(ticketId.toString(), command.status?.name, command.priority?.name, reply),
        )

        return when (
            val acquisition = requestProtection.acquire(
                operation = "admin-update:$ticketId",
                actorId = adminId,
                clientKey = idempotencyKey,
                fingerprint = fingerprint,
            )
        ) {
            is SupportTicketRequestProtection.Acquisition.Completed -> {
                if (acquisition.resultId != ticketId) {
                    throw IdempotencyConflictException("工单幂等结果无效，请更换 Idempotency-Key")
                }
                supportTicketRepository.findById(ticketId).orElse(null)
                    ?.toView()
                    ?: throw IdempotencyConflictException("工单幂等结果不存在，请更换 Idempotency-Key")
            }

            SupportTicketRequestProtection.Acquisition.Pending -> throw SupportTicketRequestInProgressException()
            is SupportTicketRequestProtection.Acquisition.Acquired -> {
                try {
                    if (ticket.status == SupportTicketStatus.CLOSED &&
                        command.status != null && command.status != SupportTicketStatus.CLOSED
                    ) {
                        throw SupportTicketStatusException("已关闭的工单不能重新打开")
                    }
                    if (reply != null) {
                        requireOpenForMessage(ticket)
                        requestProtection.requireMessageRateAllowed(
                            adminId,
                            SupportTicketMessageSender.ADMIN,
                            ticketId,
                        )
                        persistMessage(
                            ticket = ticket,
                            senderId = adminId,
                            senderType = SupportTicketMessageSender.ADMIN,
                            content = reply,
                            files = emptyList(),
                        )
                    }

                    val now = Instant.now(clock)
                    command.priority?.let { ticket.priority = it }
                    command.status?.let { nextStatus ->
                        ticket.status = nextStatus
                        when (nextStatus) {
                            SupportTicketStatus.OPEN,
                            SupportTicketStatus.IN_PROGRESS,
                            -> {
                                ticket.resolvedAt = null
                                ticket.closedAt = null
                            }

                            SupportTicketStatus.RESOLVED -> {
                                if (ticket.resolvedAt == null) ticket.resolvedAt = now
                                ticket.closedAt = null
                            }

                            SupportTicketStatus.CLOSED -> {
                                if (ticket.closedAt == null) ticket.closedAt = now
                            }
                        }
                    }
                    ticket.handledBy = adminId
                    val saved = supportTicketRepository.saveAndFlush(ticket)
                    requestProtection.completeAfterCommit(acquisition.token, ticketId)
                    saved.toView()
                } catch (ex: Exception) {
                    releaseAfterFailure(acquisition.token, ex)
                    throw ex
                }
            }
        }
    }

    private fun SupportTicket.toView(
        messages: List<SupportTicketMessageView> = emptyList(),
        messageTotalPages: Int = 0,
        messageTotalElements: Long = 0,
    ): SupportTicketView = SupportTicketView(
        id = requireNotNull(id),
        customerId = customerId,
        serviceType = serviceType,
        priority = priority,
        orderNo = order?.orderNo,
        subject = subject,
        content = content,
        status = status,
        adminReply = adminReply,
        handledBy = handledBy,
        repliedAt = repliedAt,
        resolvedAt = resolvedAt,
        closedAt = closedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = messages,
        messageTotalPages = messageTotalPages,
        messageTotalElements = messageTotalElements,
    )

    private fun messagesFor(ticketId: Long, page: Int, size: Int): Page<SupportTicketMessageView> {
        val pageable = pageRequest(page, size)
        val messages = supportTicketMessageRepository.findAllByTicket_IdOrderByCreatedAtDescIdDesc(ticketId, pageable)
        val messageIds = messages.content.map { requireNotNull(it.id) }
        val attachments = if (messageIds.isEmpty()) {
            emptyList()
        } else {
            supportTicketMessageAttachmentRepository.findAllWithFileByMessageIds(messageIds)
        }
        val attachmentsByMessageId = attachments.groupBy { requireNotNull(it.message?.id) }
        val detailsById = downloadDetailsById(attachments)
        val content = messages.content.asReversed().map { message ->
            message.toView(attachmentsByMessageId[message.id].orEmpty(), detailsById)
        }
        return PageImpl(content, pageable, messages.totalElements)
    }

    private fun sendMessage(
        ticket: SupportTicket,
        senderId: Long,
        senderType: SupportTicketMessageSender,
        command: SendSupportTicketMessageCommand,
    ): SupportTicketMessageView {
        val ticketId = requireNotNull(ticket.id)
        val content = normalizeOptionalMessageContent(command.content)
        if (content == null && command.files.isEmpty()) {
            throw ParamErrorException("消息正文和附件不能同时为空")
        }
        val idempotencyKey = requestProtection.normalizeIdempotencyKey(command.idempotencyKey)
        val fingerprint = requestProtection.fingerprint(
            fields = listOf(ticketId.toString(), senderType.name, content),
            files = command.files,
        )

        return when (
            val acquisition = requestProtection.acquire(
                operation = "${senderType.name.lowercase()}-message:$ticketId",
                actorId = senderId,
                clientKey = idempotencyKey,
                fingerprint = fingerprint,
            )
        ) {
            is SupportTicketRequestProtection.Acquisition.Completed -> {
                val message = supportTicketMessageRepository.findByIdAndTicket_Id(acquisition.resultId, ticketId)
                if (message == null || message.senderId != senderId || message.senderType != senderType) {
                    throw IdempotencyConflictException("工单消息幂等结果不存在，请更换 Idempotency-Key")
                }
                val attachments = supportTicketMessageAttachmentRepository.findAllWithFileByMessageIds(
                    listOf(requireNotNull(message.id)),
                )
                message.toView(attachments, downloadDetailsById(attachments))
            }

            SupportTicketRequestProtection.Acquisition.Pending -> throw SupportTicketRequestInProgressException()
            is SupportTicketRequestProtection.Acquisition.Acquired -> {
                try {
                    requireOpenForMessage(ticket)
                    requestProtection.requireMessageRateAllowed(senderId, senderType, ticketId)
                    requestProtection.requireAttachmentQuota(senderId, senderType, ticketId, command.files)
                    val saved = persistMessage(ticket, senderId, senderType, content, command.files)
                    supportTicketRepository.saveAndFlush(ticket)
                    requestProtection.completeAfterCommit(acquisition.token, saved.id)
                    saved
                } catch (ex: Exception) {
                    releaseAfterFailure(acquisition.token, ex)
                    throw ex
                }
            }
        }
    }

    private fun persistMessage(
        ticket: SupportTicket,
        senderId: Long,
        senderType: SupportTicketMessageSender,
        content: String?,
        files: List<MultipartFile>,
    ): SupportTicketMessageView {
        requireOpenForMessage(ticket)
        val uploaded = if (files.isEmpty()) emptyList() else fileService.upload(senderId, files)
        val message = SupportTicketMessage(
            ticket = ticket,
            senderId = senderId,
            senderType = senderType,
            content = content,
        )
        uploaded.forEach { details ->
            message.attachments += SupportTicketMessageAttachment(
                message = message,
                file = details.file,
            )
        }
        val saved = supportTicketMessageRepository.saveAndFlush(message)
        val now = Instant.now(clock)
        ticket.updatedAt = now
        when (senderType) {
            SupportTicketMessageSender.CUSTOMER -> {
                if (ticket.status == SupportTicketStatus.RESOLVED) {
                    ticket.status = SupportTicketStatus.OPEN
                    ticket.resolvedAt = null
                }
            }

            SupportTicketMessageSender.ADMIN -> {
                content?.let { ticket.adminReply = it }
                ticket.repliedAt = now
                ticket.handledBy = senderId
                if (ticket.status == SupportTicketStatus.OPEN) ticket.status = SupportTicketStatus.IN_PROGRESS
            }
        }
        val attachments = saved.attachments.toList()
        return saved.toView(attachments, downloadDetailsById(attachments))
    }

    private fun downloadDetailsById(
        attachments: Collection<SupportTicketMessageAttachment>,
    ): Map<UUID, FileDetails> {
        val files = attachments.map { requireNotNull(it.file) }.distinctBy { it.id }
        return if (files.isEmpty()) {
            emptyMap()
        } else {
            fileService.createSupportTicketDownloadLinks(files).associateBy { it.file.id }
        }
    }

    private fun SupportTicketMessage.toView(
        attachments: List<SupportTicketMessageAttachment>,
        detailsById: Map<UUID, FileDetails>,
    ): SupportTicketMessageView = SupportTicketMessageView(
        id = requireNotNull(id),
        senderId = senderId,
        senderType = senderType,
        content = content,
        attachments = attachments.map { attachment ->
            val file = requireNotNull(attachment.file)
            val details = requireNotNull(detailsById[file.id])
            SupportTicketAttachmentView(
                id = requireNotNull(attachment.id),
                fileId = file.id,
                fileName = file.originalFilename,
                contentType = file.contentType,
                sizeBytes = file.sizeBytes,
                signedDownloadUrl = details.signedDownloadUrl,
                downloadExpiresAt = details.downloadExpiresAt,
            )
        },
        createdAt = createdAt,
    )

    private fun requireOpenForMessage(ticket: SupportTicket) {
        if (ticket.status == SupportTicketStatus.CLOSED) {
            throw SupportTicketStatusException("已关闭的工单不能继续发送消息")
        }
    }
    private fun requireAuthenticatedAccountId(accountId: Long) {
        if (accountId <= 0) throw ParamErrorException("用户 ID 无效")
    }

    /** 工单按客户 ID 隔离；已存在但归属其他客户时必须返回 403。 */
    private fun customerTicket(ticketId: Long, customerId: Long): SupportTicket? {
        supportTicketRepository.findByIdAndCustomerId(ticketId, customerId)?.let { return it }
        val ticket = supportTicketRepository.findWithOrderById(ticketId) ?: return null
        if (ticket.customerId != customerId) {
            throw ForbiddenException("只能访问自己的工单")
        }
        return ticket
    }

    private fun normalizeRequiredText(value: String, maxLength: Int, fieldName: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) throw ParamErrorException("$fieldName 不能为空")
        if (normalized.length > maxLength) {
            throw ParamErrorException("$fieldName 不能超过 $maxLength 个字符")
        }
        return normalized
    }

    private fun normalizeOptionalMessageContent(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.length > CONTENT_MAX_LENGTH) {
            throw ParamErrorException("消息正文不能超过 $CONTENT_MAX_LENGTH 个字符")
        }
        return normalized
    }

    private fun normalizeOrderNo(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.length > ORDER_NO_MAX_LENGTH) {
            throw ParamErrorException("订单编号不能超过 $ORDER_NO_MAX_LENGTH 个字符")
        }
        return normalized
    }

    private fun releaseAfterFailure(token: SupportTicketRequestProtection.Token, original: Exception) {
        try {
            requestProtection.release(token)
        } catch (releaseFailure: Exception) {
            original.addSuppressed(releaseFailure)
        }
    }

    private fun pageRequest(page: Int, size: Int): PageRequest {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) {
            throw ParamErrorException("分页参数无效")
        }
        return PageRequest.of(page, size)
    }

    private companion object {
        const val SUBJECT_MAX_LENGTH = 120
        const val CONTENT_MAX_LENGTH = 5_000
        const val ORDER_NO_MAX_LENGTH = 32
        const val MAX_PAGE_SIZE = 100
    }
}
