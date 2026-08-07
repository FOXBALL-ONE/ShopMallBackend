package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.service.AdminSupportTicketQuery
import top.foxball.shopmall.service.SendSupportTicketMessageCommand
import top.foxball.shopmall.service.SupportTicketService
import top.foxball.shopmall.service.UpdateSupportTicketCommand
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * @folder 管理端/客户服务/支持工单
 */
@Validated
@RestController
@RequestMapping("/admin/api/support-tickets")
class AdminSupportTicketController(
    private val supportTicketService: SupportTicketService,
    private val userService: UserService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取管理端支持工单列表
     * @param page 分页页码
     * @param pageSize 分页每页数量
     * @param status 工单状态
     * @param serviceType 支持服务类型
     * @param priority 处理优先级
     * @param customerId 客户 ID
     * @param customerUsername 客户用户名
     * @param orderNo 订单编号
     */
    @GetMapping
    fun getAdminSupportTickets(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
        @RequestParam("status", required = false) status: SupportTicketStatus?,
        @RequestParam("service_type", required = false) serviceType: SupportServiceType?,
        @RequestParam("priority", required = false) priority: SupportTicketPriority?,
        @RequestParam("customer_id", required = false) @Min(1) customerId: Long?,
        @RequestParam("customer_username", required = false) @Size(max = 50) customerUsername: String?,
        @RequestParam("order_no", required = false) @Size(max = 32) orderNo: String?,
    ): ResponseEntity<Response> {
        data class TicketData(
            val id: Long,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            @param:JsonProperty("customer_username")
            val customerUsername: String,
            @param:JsonProperty("service_type")
            val serviceType: String,
            val priority: String,
            @param:JsonProperty("order_no")
            val orderNo: String?,
            val subject: String,
            val status: String,
            @param:JsonProperty("handled_by")
            val handledBy: Long?,
            @param:JsonProperty("handled_by_username")
            val handledByUsername: String?,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
        )

        data class Pagination(val count: Int)

        data class Response(
            val list: List<TicketData>,
            val pagination: Pagination,
        )

        val normalizedCustomerUsername = customerUsername?.trim()?.takeIf(String::isNotEmpty)
        val resolvedCustomerId = normalizedCustomerUsername?.let {
            userService.getUserByUsername(it)?.id ?: 0L
        } ?: customerId
        val pagedData = supportTicketService.listAdmin(
            adminId,
            AdminSupportTicketQuery(
                page = page - 1,
                size = pageSize,
                status = status,
                serviceType = serviceType,
                priority = priority,
                customerId = resolvedCustomerId,
                orderNo = orderNo,
            ),
        )
        val usernamesById = userService.getUsernamesByIds(
            pagedData.content.flatMap { listOfNotNull(it.customerId, it.handledBy) }.distinct(),
        )
        val list = pagedData.content.map { ticket ->
            TicketData(
                id = ticket.id,
                customerId = ticket.customerId,
                customerUsername = requireNotNull(usernamesById[ticket.customerId]) { "工单客户不存在" },
                serviceType = ticket.serviceType.name,
                priority = ticket.priority.name,
                orderNo = ticket.orderNo,
                subject = ticket.subject,
                status = ticket.status.name,
                handledBy = ticket.handledBy,
                handledByUsername = ticket.handledBy?.let(usernamesById::get),
                createdAt = ticket.createdAt,
                updatedAt = ticket.updatedAt,
            )
        }
        val rs = Response(list, Pagination(pagedData.totalPages))
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端支持工单详情
     * @param ticketId 工单 ID
     * @param messagePage 消息分页页码
     * @param messageSize 消息分页每页数量
     */
    @GetMapping("/{ticket_id}")
    fun getAdminSupportTicket(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("ticket_id") @Min(1) ticketId: Long,
        @RequestParam("message_page", defaultValue = "1") @Min(1) messagePage: Int,
        @RequestParam("message_size", defaultValue = "50") @Min(1) @Max(100) messageSize: Int,
    ): ResponseEntity<Response> {
        data class AttachmentData(
            val id: Long,
            @param:JsonProperty("file_id")
            val fileId: UUID,
            @param:JsonProperty("file_name")
            val fileName: String,
            @param:JsonProperty("content_type")
            val contentType: String?,
            @param:JsonProperty("size_bytes")
            val sizeBytes: Long,
            @param:JsonProperty("download_url")
            val downloadUrl: String,
            @param:JsonProperty("download_expires_at")
            val downloadExpiresAt: LocalDateTime,
        )

        data class MessageData(
            val id: Long,
            @param:JsonProperty("sender_id")
            val senderId: Long,
            @param:JsonProperty("sender_username")
            val senderUsername: String,
            @param:JsonProperty("sender_type")
            val senderType: String,
            val content: String?,
            val attachments: List<AttachmentData>,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
        )

        data class MessagePagination(
            val count: Int,
            val total: Long,
        )

        data class Response(
            val id: Long,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            @param:JsonProperty("customer_username")
            val customerUsername: String,
            @param:JsonProperty("service_type")
            val serviceType: String,
            val priority: String,
            @param:JsonProperty("order_no")
            val orderNo: String?,
            val subject: String,
            val content: String,
            val status: String,
            @param:JsonProperty("admin_reply")
            val adminReply: String?,
            @param:JsonProperty("handled_by")
            val handledBy: Long?,
            @param:JsonProperty("handled_by_username")
            val handledByUsername: String?,
            @param:JsonProperty("replied_at")
            val repliedAt: Instant?,
            @param:JsonProperty("resolved_at")
            val resolvedAt: Instant?,
            @param:JsonProperty("closed_at")
            val closedAt: Instant?,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
            val messages: List<MessageData>,
            @param:JsonProperty("message_pagination")
            val messagePagination: MessagePagination,
        )

        val ticket = supportTicketService.getAdmin(adminId, ticketId, messagePage - 1, messageSize)
            ?: return builder.notFound().message("工单不存在").build()
        val usernamesById = userService.getUsernamesByIds(
            buildList {
                add(ticket.customerId)
                ticket.handledBy?.let(::add)
                addAll(ticket.messages.map { it.senderId })
            }.distinct(),
        )
        val rs = Response(
            id = ticket.id,
            customerId = ticket.customerId,
            customerUsername = requireNotNull(usernamesById[ticket.customerId]) { "工单客户不存在" },
            serviceType = ticket.serviceType.name,
            priority = ticket.priority.name,
            orderNo = ticket.orderNo,
            subject = ticket.subject,
            content = ticket.content,
            status = ticket.status.name,
            adminReply = ticket.adminReply,
            handledBy = ticket.handledBy,
            handledByUsername = ticket.handledBy?.let(usernamesById::get),
            repliedAt = ticket.repliedAt,
            resolvedAt = ticket.resolvedAt,
            closedAt = ticket.closedAt,
            createdAt = ticket.createdAt,
            updatedAt = ticket.updatedAt,
            messages = ticket.messages.map { message ->
                MessageData(
                    id = message.id,
                    senderId = message.senderId,
                    senderUsername = requireNotNull(usernamesById[message.senderId]) { "消息发送人不存在" },
                    senderType = message.senderType.name,
                    content = message.content,
                    attachments = message.attachments.map { attachment ->
                        AttachmentData(
                            id = attachment.id,
                            fileId = attachment.fileId,
                            fileName = attachment.fileName,
                            contentType = attachment.contentType,
                            sizeBytes = attachment.sizeBytes,
                            downloadUrl = attachment.signedDownloadUrl,
                            downloadExpiresAt = attachment.downloadExpiresAt,
                        )
                    },
                    createdAt = message.createdAt,
                )
            },
            messagePagination = MessagePagination(ticket.messageTotalPages, ticket.messageTotalElements),
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 向支持工单发送管理员消息
     * @param ticketId 工单 ID
     * @param idempotencyKey 请求幂等键
     * @param content 消息正文，可与附件二选一
     * @param files 消息附件
     */
    @PostMapping(
        "/{ticket_id}/messages",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    fun sendAdminSupportTicketMessage(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("ticket_id") @Min(1) ticketId: Long,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) idempotencyKey: String,
        @RequestPart("content", required = false) @Size(max = 5_000) content: String?,
        @RequestPart("files", required = false) @Size(max = 10) files: List<MultipartFile>?,
    ): ResponseEntity<Response> {
        data class AttachmentData(
            val id: Long,
            @param:JsonProperty("file_id")
            val fileId: UUID,
            @param:JsonProperty("file_name")
            val fileName: String,
            @param:JsonProperty("content_type")
            val contentType: String?,
            @param:JsonProperty("size_bytes")
            val sizeBytes: Long,
            @param:JsonProperty("download_url")
            val downloadUrl: String,
            @param:JsonProperty("download_expires_at")
            val downloadExpiresAt: LocalDateTime,
        )

        data class Response(
            @param:JsonProperty("ticket_id")
            val ticketId: Long,
            val id: Long,
            @param:JsonProperty("sender_id")
            val senderId: Long,
            @param:JsonProperty("sender_username")
            val senderUsername: String,
            @param:JsonProperty("sender_type")
            val senderType: String,
            val content: String?,
            val attachments: List<AttachmentData>,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
        )

        val message = supportTicketService.sendAdminMessage(
            adminId,
            ticketId,
            SendSupportTicketMessageCommand(
                content = content,
                files = files ?: emptyList(),
                idempotencyKey = idempotencyKey,
            ),
        ) ?: return builder.notFound().message("工单不存在").build()
        val senderUsername = requireNotNull(userService.getUsernameById(message.senderId)) { "消息发送人不存在" }
        val rs = Response(
            ticketId = ticketId,
            id = message.id,
            senderId = message.senderId,
            senderUsername = senderUsername,
            senderType = message.senderType.name,
            content = message.content,
            attachments = message.attachments.map { attachment ->
                AttachmentData(
                    id = attachment.id,
                    fileId = attachment.fileId,
                    fileName = attachment.fileName,
                    contentType = attachment.contentType,
                    sizeBytes = attachment.sizeBytes,
                    downloadUrl = attachment.signedDownloadUrl,
                    downloadExpiresAt = attachment.downloadExpiresAt,
                )
            },
            createdAt = message.createdAt,
        )
        return builder.created().data(rs).build()
    }

    /**
     * @api 处理支持工单
     * @param ticketId 工单 ID
     * @param idempotencyKey 请求幂等键
     * @param status 工单状态
     * @param priority 处理优先级
     * @param reply 管理员公开回复
     */
    @PutMapping("/{ticket_id}")
    fun updateSupportTicket(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("ticket_id") @Min(1) ticketId: Long,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) idempotencyKey: String,
        @RequestParam("status", required = false) status: SupportTicketStatus?,
        @RequestParam("priority", required = false) priority: SupportTicketPriority?,
        @RequestParam("reply", required = false) @Size(max = 5_000) reply: String?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            @param:JsonProperty("customer_username")
            val customerUsername: String,
            @param:JsonProperty("service_type")
            val serviceType: String,
            val priority: String,
            @param:JsonProperty("order_no")
            val orderNo: String?,
            val subject: String,
            val content: String,
            val status: String,
            @param:JsonProperty("admin_reply")
            val adminReply: String?,
            @param:JsonProperty("handled_by")
            val handledBy: Long?,
            @param:JsonProperty("handled_by_username")
            val handledByUsername: String?,
            @param:JsonProperty("replied_at")
            val repliedAt: Instant?,
            @param:JsonProperty("resolved_at")
            val resolvedAt: Instant?,
            @param:JsonProperty("closed_at")
            val closedAt: Instant?,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
        )

        val ticket = supportTicketService.updateByAdmin(
            adminId,
            ticketId,
            UpdateSupportTicketCommand(
                status = status,
                priority = priority,
                reply = reply,
                idempotencyKey = idempotencyKey,
            ),
        ) ?: return builder.notFound().message("工单不存在").build()
        val usernamesById = userService.getUsernamesByIds(
            listOfNotNull(ticket.customerId, ticket.handledBy).distinct(),
        )
        val rs = Response(
            id = ticket.id,
            customerId = ticket.customerId,
            customerUsername = requireNotNull(usernamesById[ticket.customerId]) { "工单客户不存在" },
            serviceType = ticket.serviceType.name,
            priority = ticket.priority.name,
            orderNo = ticket.orderNo,
            subject = ticket.subject,
            content = ticket.content,
            status = ticket.status.name,
            adminReply = ticket.adminReply,
            handledBy = ticket.handledBy,
            handledByUsername = ticket.handledBy?.let(usernamesById::get),
            repliedAt = ticket.repliedAt,
            resolvedAt = ticket.resolvedAt,
            closedAt = ticket.closedAt,
            createdAt = ticket.createdAt,
            updatedAt = ticket.updatedAt,
        )
        return builder.ok().data(rs).build()
    }
}
