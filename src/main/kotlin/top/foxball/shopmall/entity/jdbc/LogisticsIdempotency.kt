package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

/**
 * 物流写操作的数据库幂等记录。
 *
 * 唯一约束以操作人、操作类型和幂等键为维度，防止重复创建或修改运单；请求摘要用于识别
 * 同一幂等键被用于不同请求的冲突，并关联首次成功操作对应的运单。
 */
@Entity
@Table(
    name = "logistics_idempotency",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_logistics_idempotency",
            columnNames = ["actor_id", "operation", "idempotency_key"],
        ),
    ],
)
class LogisticsIdempotency(
    /** 数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 发起本次物流操作的用户标识。 */
    @Column(name = "actor_id", nullable = false)
    var actorId: Long = 0,

    /** 物流操作类型，用于区分创建、取消等不同写操作。 */
    @Column(nullable = false, length = 48)
    var operation: String = "",

    /** 调用方提供的幂等键。 */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    var idempotencyKey: String = "",

    /** 请求内容摘要，用于检测同一幂等键对应不同参数的冲突。 */
    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String = "",

    /** 首次成功操作关联的运单标识。 */
    @Column(name = "shipment_id", nullable = false)
    var shipmentId: Long = 0,

    /** 幂等记录创建时间，由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
