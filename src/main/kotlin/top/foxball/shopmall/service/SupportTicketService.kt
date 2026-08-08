package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class CreateSupportTicketCommand(
    val serviceType: SupportServiceType,
    val priority: SupportTicketPriority = SupportTicketPriority.LOW,
    val orderNo: String? = null,
    val subject: String,
    val content: String,
    val idempotencyKey: String,
)

data class SupportTicketPageQuery(
    val page: Int = 0,
    val size: Int = 20,
    val status: SupportTicketStatus? = null,
    val serviceType: SupportServiceType? = null,
    val priority: SupportTicketPriority? = null,
)

data class AdminSupportTicketQuery(
    val page: Int = 0,
    val size: Int = 20,
    val status: SupportTicketStatus? = null,
    val serviceType: SupportServiceType? = null,
    val priority: SupportTicketPriority? = null,
    val customerId: Long? = null,
    val orderNo: String? = null,
)

data class UpdateSupportTicketCommand(
    val status: SupportTicketStatus? = null,
    val priority: SupportTicketPriority? = null,
    val reply: String? = null,
    val idempotencyKey: String,
)

data class SendSupportTicketMessageCommand(
    val content: String? = null,
    val files: List<MultipartFile> = emptyList(),
    val idempotencyKey: String,
)

data class SupportTicketAttachmentView(
    val id: Long,
    val fileId: UUID,
    val fileName: String,
    val contentType: String?,
    val sizeBytes: Long,
    val signedDownloadUrl: String,
    val downloadExpiresAt: LocalDateTime,
)

data class SupportTicketMessageView(
    val id: Long,
    val senderId: Long,
    val senderType: SupportTicketMessageSender,
    val content: String?,
    val attachments: List<SupportTicketAttachmentView>,
    val createdAt: Instant?,
)

data class SupportTicketView(
    val id: Long,
    val customerId: Long,
    val serviceType: SupportServiceType,
    val priority: SupportTicketPriority,
    val orderNo: String?,
    val subject: String,
    val content: String,
    val status: SupportTicketStatus,
    val adminReply: String?,
    val handledBy: Long?,
    val repliedAt: Instant?,
    val resolvedAt: Instant?,
    val closedAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val messages: List<SupportTicketMessageView> = emptyList(),
    val messageTotalPages: Int = 0,
    val messageTotalElements: Long = 0,
)

/** 客户支持工单服务，负责工单创建、归属校验、查询和管理端处理。 */
interface SupportTicketService {
    /** 创建客户工单；售后支持必须关联当前客户名下的订单。 */
    fun create(customerId: Long, command: CreateSupportTicketCommand): SupportTicketView

    /** 分页查询当前客户自己的工单。 */
    fun listCustomer(customerId: Long, query: SupportTicketPageQuery): Page<SupportTicketView>

    /** 查询当前客户自己的工单；不存在时返回 `null`，属于其他客户时拒绝访问。 */
    fun getCustomer(
        customerId: Long,
        ticketId: Long,
        messagePage: Int = 0,
        messageSize: Int = 50,
    ): SupportTicketView?

    /** 客户向自己的工单发送正文或附件消息。 */
    fun sendCustomerMessage(
        customerId: Long,
        ticketId: Long,
        command: SendSupportTicketMessageCommand,
    ): SupportTicketMessageView?

    /** 由客户关闭自己的工单；不存在时返回 `null`，属于其他客户时拒绝访问。 */
    fun closeByCustomer(customerId: Long, ticketId: Long): SupportTicketView?

    /** 管理员按条件分页查询全部工单。 */
    fun listAdmin(adminId: Long, query: AdminSupportTicketQuery): Page<SupportTicketView>

    /** 管理员查询指定工单；不存在时返回 `null`。 */
    fun getAdmin(
        adminId: Long,
        ticketId: Long,
        messagePage: Int = 0,
        messageSize: Int = 50,
    ): SupportTicketView?

    /** 管理员向指定工单发送正文或附件消息。 */
    fun sendAdminMessage(
        adminId: Long,
        ticketId: Long,
        command: SendSupportTicketMessageCommand,
    ): SupportTicketMessageView?

    /** 管理员更新工单状态、优先级或公开回复；不存在时返回 `null`。 */
    fun updateByAdmin(
        adminId: Long,
        ticketId: Long,
        command: UpdateSupportTicketCommand,
    ): SupportTicketView?
}

