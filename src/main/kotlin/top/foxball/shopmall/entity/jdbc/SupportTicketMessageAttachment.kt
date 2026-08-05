package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

/** 工单消息与已存储文件之间的关联；删除已关联文件会受数据库外键保护。 */
@Entity
@Table(
    name = "support_ticket_message_attachments",
    indexes = [
        Index(name = "idx_support_ticket_message_attachments_message", columnList = "message_id"),
        Index(name = "idx_support_ticket_message_attachments_file", columnList = "file_id"),
    ],
)
class SupportTicketMessageAttachment(
    /** 附件关联数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 所属消息。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "message_id",
        nullable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "fk_support_ticket_message_attachment_message"),
    )
    var message: SupportTicketMessage? = null,

    /** 由现有文件服务保存的文件元数据。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "file_id",
        nullable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "fk_support_ticket_message_attachment_file"),
    )
    var file: StoredFile? = null,

    /** 关联创建时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
