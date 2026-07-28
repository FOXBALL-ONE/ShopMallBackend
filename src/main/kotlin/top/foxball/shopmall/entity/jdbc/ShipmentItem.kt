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

/**
 * 分配到运单中的订单商品行。
 *
 * 保存订单行的不可变快照和本次发货数量，支持一个订单拆分为多张运单；分配状态和释放原因用于
 * 处理取消运单或回补库存等履约补偿流程。
 */
@Entity
@Table(name = "shipment_items")
class ShipmentItem(
    /** 运单明细的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 所属运单，仅用于持久化关联，序列化时忽略。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    var shipment: Shipment? = null,

    /** 对应订单明细的标识。 */
    @field:Min(1)
    @Column(name = "order_item_id", nullable = false, updatable = false)
    var orderItemId: Long = 0,

    /** 分配时保存的订单明细快照。 */
    @Column(name = "order_item_snapshot", nullable = false, length = 2000, updatable = false)
    var orderItemSnapshot: String = "",

    /** 本次运单实际分配的发货数量。 */
    @field:Min(1)
    @Column(nullable = false, updatable = false)
    var quantity: Int = 1,

    /** 订单明细在当前运单中的分配或释放状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_status", nullable = false, length = 16)
    var allocationStatus: AllocationStatus = AllocationStatus.ALLOCATED,

    /** 分配被释放的时间；仍处于分配状态时为空。 */
    @Column(name = "released_at")
    var releasedAt: Instant? = null,

    /** 释放分配时记录的原因。 */
    @Column(name = "release_reason", length = 200)
    var releaseReason: String? = null,

    /** 运单明细创建时间，由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
