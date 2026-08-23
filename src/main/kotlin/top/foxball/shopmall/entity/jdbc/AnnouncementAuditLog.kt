package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/** 公告管理操作的追加式审计记录。 */
@Entity
@Table(
    name = "announcement_audit_logs",
    indexes = [
        Index(name = "idx_announcement_audit_logs_announcement_created", columnList = "announcement_id,created_at"),
        Index(name = "idx_announcement_audit_logs_operator_created", columnList = "operator_id,created_at"),
    ],
)
class AnnouncementAuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "announcement_id", nullable = false, updatable = false)
    var announcementId: Long = 0,

    @Column(name = "operator_id", nullable = false, updatable = false)
    var operatorId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var action: Action = Action.CREATED,

    @Lob
    @Column(name = "before_snapshot")
    var beforeSnapshot: String? = null,

    @Lob
    @Column(name = "after_snapshot", nullable = false)
    var afterSnapshot: String = "",

    @Column(length = 255)
    var reason: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
) {
    enum class Action {
        CREATED,
        UPDATED,
        PUBLISHED,
        SYSTEM_PUBLISHED,
        SYSTEM_EXPIRED,
        OFFLINE,
        ARCHIVED,
        DELETED,
        COPIED,
    }
}
