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

/**
 * 订单履约产生的运单实体。
 *
 * 一张运单关联一个订单、承运商、跟踪号和收货地址快照，并记录面单创建、跟踪轮询及配送状态；
 * [version] 用于并发更新时的乐观锁控制。
 */
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
    /** 运单的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 面向运营和客户展示的唯一运单号。 */
    @Column(name = "shipment_no", nullable = false, unique = true, length = 32)
    var shipmentNo: String = "",

    /** 所属订单的标识。 */
    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long = 0,

    /** 负责配送的承运商代码。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "carrier_code", nullable = false, length = 24)
    var carrierCode: CarrierCode = CarrierCode.MANUAL,

    /** 承运商分配的原始跟踪号。 */
    @Column(name = "tracking_no", length = 64)
    var trackingNo: String? = null,

    /** 用于去重和查询的标准化跟踪号。 */
    @Column(name = "tracking_no_normalized", length = 64)
    var trackingNoNormalized: String? = null,

    /** 面单创建、运输和取消等履约生命周期状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: ShipmentStatus = ShipmentStatus.LABEL_PENDING,

    /** 创建运单时复制的收货地址快照。 */
    @field:Valid
    @Embedded
    var shippingAddress: OrderShippingAddress = OrderShippingAddress(),

    /** 承运商确认发货的时间。 */
    @Column(name = "shipped_at")
    var shippedAt: Instant? = null,

    /** 承运商确认签收或配送完成的时间。 */
    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null,

    /** 承运商生成的面单文件地址。 */
    @Column(name = "carrier_label_url", length = 512)
    var carrierLabelUrl: String? = null,

    /** 供运营或客户查看物流状态的跟踪页面地址。 */
    @Column(name = "tracking_url", length = 512)
    var trackingUrl: String? = null,

    /** 最近一次承运商返回的原始跟踪状态代码。 */
    @Column(name = "last_track_status", length = 64)
    var lastTrackStatus: String? = null,

    /** 最近一次跟踪事件发生的时间。 */
    @Column(name = "last_track_at")
    var lastTrackAt: Instant? = null,

    /** 最近一次跟踪事件的承运商唯一标识。 */
    @Column(name = "last_track_event_id", length = 128)
    var lastTrackEventId: String? = null,

    /** 最近一次跟踪事件报告的位置。 */
    @Column(name = "last_track_location", length = 200)
    var lastTrackLocation: String? = null,

    /** 下次应执行物流轮询的时间。 */
    @Column(name = "next_track_poll_at")
    var nextTrackPollAt: Instant? = null,

    /** 当前持有跟踪轮询租约的工作节点标识。 */
    @Column(name = "poll_lease_owner", length = 128)
    var pollLeaseOwner: String? = null,

    /** 跟踪轮询租约失效的时间。 */
    @Column(name = "poll_lease_until")
    var pollLeaseUntil: Instant? = null,

    /** 连续跟踪查询失败次数，用于退避和异常告警。 */
    @Column(name = "consecutive_track_failures", nullable = false)
    var consecutiveTrackFailures: Int = 0,

    /** 最近一次跟踪查询失败的错误信息。 */
    @Column(name = "last_track_error", length = 500)
    var lastTrackError: String? = null,

    /** 创建该运单的用户标识。 */
    @field:Min(1)
    @Column(name = "created_by", nullable = false)
    var createdBy: Long = 0,

    /** 取消运单时记录的原因。 */
    @Column(name = "cancel_reason", length = 200)
    var cancelReason: String? = null,

    /** 供运营人员记录的运单备注。 */
    @Column(length = 200)
    var note: String? = null,

    /** 运单创建时间，由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    /** 运单最后更新时间，由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,

    /** 用于并发更新检测的乐观锁版本号。 */
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
