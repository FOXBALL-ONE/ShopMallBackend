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
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "actor_id", nullable = false)
    var actorId: Long = 0,

    @Column(nullable = false, length = 48)
    var operation: String = "",

    @Column(name = "idempotency_key", nullable = false, length = 128)
    var idempotencyKey: String = "",

    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String = "",

    @Column(name = "shipment_id", nullable = false)
    var shipmentId: Long = 0,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
