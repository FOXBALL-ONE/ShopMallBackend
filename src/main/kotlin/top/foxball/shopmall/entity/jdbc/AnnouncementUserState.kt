package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** 已登录客户对公告的已读、关闭或确认状态。 */
@Entity
@Table(
    name = "announcement_user_states",
    indexes = [
        Index(name = "idx_announcement_user_states_user_updated", columnList = "user_id,updated_at"),
        Index(name = "idx_announcement_user_states_announcement", columnList = "announcement_id"),
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_announcement_user_states_announcement_user",
            columnNames = ["announcement_id", "user_id"],
        ),
    ],
)
class AnnouncementUserState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "announcement_id", nullable = false, updatable = false)
    var announcementId: Long = 0,

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: State = State.SEEN,

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "dismissed_at")
    var dismissedAt: LocalDateTime? = null,

    @Column(name = "acknowledged_at")
    var acknowledgedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,
) {
    enum class State {
        SEEN,
        DISMISSED,
        ACKNOWLEDGED,
    }
}
