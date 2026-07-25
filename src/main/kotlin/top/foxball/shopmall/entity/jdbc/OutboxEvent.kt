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
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(
    name = "domain_outbox",
    indexes = [Index(name = "idx_outbox_status_created", columnList = "status,created_at")],
)
class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "aggregate_type", nullable = false, length = 32)
    var aggregateType: String = "",

    @Column(name = "aggregate_id", nullable = false)
    var aggregateId: Long = 0,

    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: String = "",

    @Column(nullable = false, columnDefinition = "text")
    var payload: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.PENDING,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null,

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
) {
    enum class Status { PENDING, SENT, ACKNOWLEDGED, NEEDS_REPLAY, DEAD }
}
