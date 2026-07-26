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

// 订单幂等兜底表：作为 Redis 之外的二道防线，保证相同 customerId + idempotencyKey 只创建一个订单。
// Redis SETNX 在 afterCommit 回写失败、TTL 过期后会放行重复下单，DB 唯一约束在此兜底。
@Entity
@Table(
    name = "order_idempotency",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_order_idempotency",
            columnNames = ["customer_id", "idempotency_key"],
        ),
    ],
)
class OrderIdempotency(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "customer_id", nullable = false)
    var customerId: Long = 0,

    @Column(name = "idempotency_key", nullable = false, length = 128)
    var idempotencyKey: String = "",

    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String = "",

    @Column(name = "order_no", nullable = false, length = 32)
    var orderNo: String = "",

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
