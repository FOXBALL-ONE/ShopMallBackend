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
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(
    name = "shipment_tracks",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_track_shipment_event",
            columnNames = ["shipment_id", "carrier_event_id"],
        ),
    ],
    indexes = [Index(name = "idx_track_shipment_ts", columnList = "shipment_id,occurred_at,carrier_event_id")],
)
class ShipmentTrack(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    var shipment: Shipment? = null,

    @Column(name = "carrier_event_id", nullable = false, length = 128)
    var carrierEventId: String = "",

    @Column(name = "status_code", nullable = false, length = 64)
    var statusCode: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_status", nullable = false, length = 32)
    var normalizedStatus: NormalizedTrackingStatus = NormalizedTrackingStatus.UNKNOWN,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var source: TrackSource = TrackSource.WEBHOOK,

    @Column(length = 200)
    var location: String? = null,

    @Column(length = 500)
    var description: String? = null,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH,

    @Column(columnDefinition = "text")
    var raw: String? = null,

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    var receivedAt: Instant? = null,
)
