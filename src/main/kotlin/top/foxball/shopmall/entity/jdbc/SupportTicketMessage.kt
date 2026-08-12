package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

/** 工单中的一条客户或管理员消息；消息可以只包含正文、只包含附件，或同时包含两者。 */
@Entity
@Table(
    name = "support_ticket_messages",
    indexes = [
        Index(name = "idx_support_ticket_messages_ticket_created", columnList = "ticket_id,created_at"),
        Index(name = "idx_support_ticket_messages_sender_created", columnList = "sender_id,created_at"),
    ],
)
class SupportTicketMessage(
    /** 消息数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 消息所属工单。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    var ticket: SupportTicket? = null,

    /** 发送消息的客户或管理员 ID。 */
    @field:Min(1)
    @Column(name = "sender_id", nullable = false, updatable = false)
    var senderId: Long = 0,

    /** 消息发送者类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 16, updatable = false)
    var senderType: SupportTicketMessageSender = SupportTicketMessageSender.CUSTOMER,

    /** 消息正文；允许为空，以支持纯附件消息。 */
    @field:Size(max = 5_000)
    @Column(length = 5_000)
    var content: String? = null,

    /** 消息附件，文件元数据由现有文件服务管理。 */
    @OneToMany(mappedBy = "message", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    var attachments: MutableList<@Valid SupportTicketMessageAttachment> = mutableListOf(),

    /** 消息创建时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
) {
    /** 消息必须包含非空正文或至少一个附件。 */
    @get:AssertTrue(message = "消息正文和附件不能同时为空")
    val hasContentOrAttachment: Boolean
        get() = content?.isNotBlank() == true || attachments.isNotEmpty()
}

/** 工单消息发送者类型。 */
enum class SupportTicketMessageSender {
    CUSTOMER,
    ADMIN,
}

