package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(name = "stripe_webhook_events")
class StripeWebhookEvent(
    @Id
    @Column(name = "event_id", length = 255)
    var eventId: String = "",

    @Column(name = "event_type", nullable = false, length = 128)
    var eventType: String = "",

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    var receivedAt: Instant? = null,
)
