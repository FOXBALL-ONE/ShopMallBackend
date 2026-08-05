package top.foxball.shopmall.controller

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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.service.CreateSupportTicketCommand
import top.foxball.shopmall.service.SendSupportTicketMessageCommand
import top.foxball.shopmall.service.SupportTicketPageQuery
import top.foxball.shopmall.service.SupportTicketService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * @folder 客户服务/支持工单
 */
@Validated
@RestController
class SupportTicketController(
    private val supportTicketService: SupportTicketService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/support-tickets/options")
    fun getSupportTicketOptions(): ResponseEntity<Response> {
        data class OptionData(val value: String, val label: String)
        data class Response(
            @param:JsonProperty("service_types")
            val serviceTypes: List<OptionData>,
            val priorities: List<OptionData>,
            @param:JsonProperty("default_priority")
            val defaultPriority: String,
        )

        val rs = Response(
            serviceTypes = listOf(
                OptionData(SupportServiceType.PRE_SALES.name, "售前咨询"),
                OptionData(SupportServiceType.AFTER_SALES.name, "售后支持"),
            ),
            priorities = listOf(
                OptionData(SupportTicketPriority.LOW.name, "低"),
                OptionData(SupportTicketPriority.MEDIUM.name, "中"),
                OptionData(SupportTicketPriority.HIGH.name, "高"),
            ),
            defaultPriority = SupportTicketPriority.LOW.name,
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/api/support-tickets")
    fun createSupportTicket(
        @AuthenticationPrincipal userId: Long,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) idempotencyKey: String,
        @RequestParam("service_type") serviceType: SupportServiceType,
        @RequestParam("priority", defaultValue = "LOW") priority: SupportTicketPriority,
        @RequestParam("order_no", required = false) @Size(max = 32) orderNo: String?,
        @RequestParam("subject") @NotBlank @Size(max = 120) subject: String,
        @RequestParam("content") @NotBlank @Size(max = 5_000) content: String,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("customer_id")
            val customerId: Long,
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

        val ticket = supportTicketService.create(
            userId,
            CreateSupportTicketCommand(serviceType, priority, orderNo, subject, content, idempotencyKey),
        )
        val rs = Response(
            id = ticket.id,
            customerId = ticket.customerId,
            serviceType = ticket.serviceType.name,
            priority = ticket.priority.name,
            orderNo = ticket.orderNo,
            subject = ticket.subject,
            content = ticket.content,
            status = ticket.status.name,
            adminReply = ticket.adminReply,
            repliedAt = ticket.repliedAt,
            resolvedAt = ticket.resolvedAt,
            closedAt = ticket.closedAt,
            createdAt = ticket.createdAt,
            updatedAt = ticket.updatedAt,
        )
        return builder.created().data(rs).build()
    }

    @GetMapping("/api/support-tickets")
    fun getMySupportTickets(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
        @RequestParam("status", required = false) status: SupportTicketStatus?,
        @RequestParam("service_type", required = false) serviceType: SupportServiceType?,
        @RequestParam("priority", required = false) priority: SupportTicketPriority?,
    ): ResponseEntity<Response> {
        data class TicketData(
            val id: Long,
            @param:JsonProperty("service_type")
            val serviceType: String,
            val priority: String,
            @param:JsonProperty("order_no")
            val orderNo: String?,
            val subject: String,
            val status: String,
            @param:JsonProperty("admin_reply")
            val adminReply: String?,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
        )
        data class Pagination(val count: Int)
        data class Response(val list: List<TicketData>, val pagination: Pagination)

        val pagedData = supportTicketService.listCustomer(
            userId,
            SupportTicketPageQuery(page - 1, pageSize, status, serviceType, priority),
        )
        val list = pagedData.content.map { ticket ->
            TicketData(
                id = ticket.id,
                serviceType = ticket.serviceType.name,
                priority = ticket.priority.name,
                orderNo = ticket.orderNo,
                subject = ticket.subject,
                status = ticket.status.name,
                adminReply = ticket.adminReply,
                createdAt = ticket.createdAt,
                updatedAt = ticket.updatedAt,
            )
        }
        val rs = Response(list, Pagination(pagedData.totalPages))
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取当前客户的支持工单详情
     * @param ticketId 工单 ID
     * @param messagePage 消息分页页码
     * @param messageSize 消息分页每页数量
     */
    @GetMapping("/api/support-tickets/{ticket_id}")
    fun getMySupportTicket(
        @AuthenticationPrincipal userId: Long,
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

        val ticket = supportTicketService.getCustomer(userId, ticketId, messagePage - 1, messageSize)
            ?: return builder.notFound().message("工单不存在").build()
        val rs = Response(
            id = ticket.id,
            customerId = ticket.customerId,
            serviceType = ticket.serviceType.name,
            priority = ticket.priority.name,
            orderNo = ticket.orderNo,
            subject = ticket.subject,
            content = ticket.content,
            status = ticket.status.name,
            adminReply = ticket.adminReply,
            repliedAt = ticket.repliedAt,
            resolvedAt = ticket.resolvedAt,
            closedAt = ticket.closedAt,
            createdAt = ticket.createdAt,
            updatedAt = ticket.updatedAt,
            messages = ticket.messages.map { message ->
                MessageData(
                    id = message.id,
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
     * @api 向当前客户的支持工单发送消息
     * @param ticketId 工单 ID
     * @param content 消息正文，可与附件二选一
     * @param files 消息附件
     */
    @PostMapping(
        "/api/support-tickets/{ticket_id}/messages",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    fun sendMySupportTicketMessage(
        @AuthenticationPrincipal userId: Long,
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

            @param:JsonProperty("sender_type")
            val senderType: String,
            val content: String?,
            val attachments: List<AttachmentData>,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
        )

        val message = supportTicketService.sendCustomerMessage(
            userId,
            ticketId,
            SendSupportTicketMessageCommand(content = content, files = files ?: emptyList(), idempotencyKey = idempotencyKey),
        ) ?: return builder.notFound().message("工单不存在").build()
        val rs = Response(
            ticketId = ticketId,
            id = message.id,
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

    @PostMapping("/api/support-tickets/{ticket_id}/close")
    fun closeMySupportTicket(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("ticket_id") @Min(1) ticketId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val status: String,
            @param:JsonProperty("closed_at")
            val closedAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
        )

        val ticket = supportTicketService.closeByCustomer(userId, ticketId)
            ?: return builder.notFound().message("工单不存在").build()
        val rs = Response(
            id = ticket.id,
            status = ticket.status.name,
            closedAt = ticket.closedAt,
            updatedAt = ticket.updatedAt,
        )
        return builder.ok().data(rs).build()
    }
}

