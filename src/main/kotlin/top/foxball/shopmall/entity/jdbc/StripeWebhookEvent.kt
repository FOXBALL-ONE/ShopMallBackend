package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

/**
 * Stripe 回调事件的去重记录。
 *
 * 以 Stripe 事件 ID 作为主键，确保重复投递的同一回调只会推进一次本地订单状态；同时保留事件
 * 类型和接收时间，供审计与故障排查使用。
 */
@Entity
@Table(name = "stripe_webhook_events")
class StripeWebhookEvent(
    /** Stripe 提供的全局事件标识，同时作为去重主键。 */
    @Id
    @Column(name = "event_id", length = 255)
    var eventId: String = "",

    /** Stripe 事件类型，例如支付成功或支付失败。 */
    @Column(name = "event_type", nullable = false, length = 128)
    var eventType: String = "",

    /** 系统首次接收该 Stripe 回调的时间。 */
    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    var receivedAt: Instant? = null,
)
