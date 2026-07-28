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

/**
 * 事务外盒事件，用于可靠地将领域变更交给异步处理器。
 *
 * 业务事务提交时先持久化事件，后台中继根据状态、重试次数和下次尝试时间投递；确认完成后保留
 * 记录以支持审计和必要时的事件重放。
 */
@Entity
@Table(
    name = "domain_outbox",
    indexes = [Index(name = "idx_outbox_status_created", columnList = "status,created_at")],
)
class OutboxEvent(
    /** 外盒事件的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 产生事件的聚合类型，例如订单或运单。 */
    @Column(name = "aggregate_type", nullable = false, length = 32)
    var aggregateType: String = "",

    /** 产生事件的聚合标识。 */
    @Column(name = "aggregate_id", nullable = false)
    var aggregateId: Long = 0,

    /** 领域事件类型，用于分派给对应的异步处理器。 */
    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: String = "",

    /** 事件负载，使用序列化文本保存。 */
    @Column(nullable = false, columnDefinition = "text")
    var payload: String = "",

    /** 事件投递与确认状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.PENDING,

    /** 已执行的投递尝试次数。 */
    @Column(nullable = false)
    var attempts: Int = 0,

    /** 下次允许尝试投递的时间。 */
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null,

    /** 消费端确认事件已完成处理的时间。 */
    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null,

    /** 外盒事件创建时间，由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
) {
    enum class Status { PENDING, SENT, ACKNOWLEDGED, NEEDS_REPLAY, DEAD }
}
