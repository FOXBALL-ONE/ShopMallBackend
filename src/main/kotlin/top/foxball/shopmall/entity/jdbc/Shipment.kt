package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(
    name = "shipments",
    indexes = [
        Index(name = "idx_shipment_no", columnList = "shipment_no"),
        Index(name = "idx_shipment_order_status", columnList = "order_id,status"),
        Index(name = "idx_shipment_poll_due", columnList = "next_track_poll_at,status"),
    ],
)
class Shipment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "shipment_no", nullable = false, unique = true, length = 32)
    var shipmentNo: String = "",

    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier_code", nullable = false, length = 24)
    var carrierCode: CarrierCode = CarrierCode.MANUAL,

    @Column(name = "tracking_no", length = 64)
    var trackingNo: String? = null,

    @Column(name = "tracking_no_normalized", length = 64)
    var trackingNoNormalized: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: ShipmentStatus = ShipmentStatus.LABEL_PENDING,

    @field:Valid
    @Embedded
    var shippingAddress: OrderShippingAddress = OrderShippingAddress(),

    @Column(name = "shipped_at")
    var shippedAt: Instant? = null,

    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null,

    @Column(name = "carrier_label_url", length = 512)
    var carrierLabelUrl: String? = null,

    @Column(name = "tracking_url", length = 512)
    var trackingUrl: String? = null,

    @Column(name = "last_track_status", length = 64)
    var lastTrackStatus: String? = null,

    @Column(name = "last_track_at")
    var lastTrackAt: Instant? = null,

    @Column(name = "last_track_event_id", length = 128)
    var lastTrackEventId: String? = null,

    @Column(name = "last_track_location", length = 200)
    var lastTrackLocation: String? = null,

    @Column(name = "next_track_poll_at")
    var nextTrackPollAt: Instant? = null,

    @Column(name = "poll_lease_owner", length = 128)
    var pollLeaseOwner: String? = null,

    @Column(name = "poll_lease_until")
    var pollLeaseUntil: Instant? = null,

    @Column(name = "consecutive_track_failures", nullable = false)
    var consecutiveTrackFailures: Int = 0,

    @Column(name = "last_track_error", length = 500)
    var lastTrackError: String? = null,

    @field:Min(1)
    @Column(name = "created_by", nullable = false)
    var createdBy: Long = 0,

    @Column(name = "cancel_reason", length = 200)
    var cancelReason: String? = null,

    @Column(length = 200)
    var note: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
