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

/**
 * 承运商返回的单条运单跟踪事件。
 *
 * 以运单和承运商事件标识组成唯一约束，统一去重 webhook 与轮询结果；保留原始状态和载荷，
 * 以便排查承运商状态映射或事件顺序问题。
 */
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
    /** 跟踪事件的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 所属运单，仅用于持久化关联，序列化时忽略。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    var shipment: Shipment? = null,

    /** 承运商侧的事件唯一标识，用于同一运单内去重。 */
    @Column(name = "carrier_event_id", nullable = false, length = 128)
    var carrierEventId: String = "",

    /** 承运商返回的原始物流状态代码。 */
    @Column(name = "status_code", nullable = false, length = 64)
    var statusCode: String = "",

    /** 映射为系统统一语义后的物流状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_status", nullable = false, length = 32)
    var normalizedStatus: NormalizedTrackingStatus = NormalizedTrackingStatus.UNKNOWN,

    /** 跟踪事件来源，例如 webhook 或主动轮询。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var source: TrackSource = TrackSource.WEBHOOK,

    /** 承运商报告的事件发生地点。 */
    @Column(length = 200)
    var location: String? = null,

    /** 承运商提供的事件文字说明。 */
    @Column(length = 500)
    var description: String? = null,

    /** 跟踪事件在承运商侧发生的时间。 */
    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH,

    /** 承运商原始事件载荷，用于排查和重新映射。 */
    @Column(columnDefinition = "text")
    var raw: String? = null,

    /** 系统接收并持久化该跟踪事件的时间。 */
    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    var receivedAt: Instant? = null,
)
