package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.Min
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(name = "shipment_items")
class ShipmentItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    var shipment: Shipment? = null,

    @field:Min(1)
    @Column(name = "order_item_id", nullable = false, updatable = false)
    var orderItemId: Long = 0,

    @Column(name = "order_item_snapshot", nullable = false, length = 2000, updatable = false)
    var orderItemSnapshot: String = "",

    @field:Min(1)
    @Column(nullable = false, updatable = false)
    var quantity: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_status", nullable = false, length = 16)
    var allocationStatus: AllocationStatus = AllocationStatus.ALLOCATED,

    @Column(name = "released_at")
    var releasedAt: Instant? = null,

    @Column(name = "release_reason", length = 200)
    var releaseReason: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
