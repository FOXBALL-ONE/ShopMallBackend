package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import java.time.Instant

interface ShipmentRepository : JpaRepository<Shipment, Long> {
    fun findByShipmentNo(shipmentNo: String): Shipment?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Shipment?

    fun findAllByOrderIdOrderByCreatedAtAsc(orderId: Long): List<Shipment>

    fun findByCarrierCodeAndTrackingNoNormalized(
        carrierCode: CarrierCode,
        trackingNoNormalized: String,
    ): Shipment?

    @Query(
        "select s from Shipment s where s.nextTrackPollAt is not null " +
            "and s.nextTrackPollAt <= :now and s.status in :statuses order by s.nextTrackPollAt",
    )
    fun findDueForTracking(
        @Param("now") now: Instant,
        @Param("statuses") statuses: Collection<ShipmentStatus>,
        pageable: Pageable,
    ): List<Shipment>

    // 条件 UPDATE 是权威幂等与防回退点：只接受单向前进的源状态，重复/乱序事件返回 0 行。
    // clearAutomatically=true 会清空持久化上下文，调用方不得再用旧实体生成 payload 或响应。

    /** 登记下次轮询时间（仅 polling 适配器在发出时调用）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.nextTrackPollAt = :at where s.id = :id",
    )
    fun scheduleNextPoll(
        @Param("id") id: Long,
        @Param("at") at: Instant,
    ): Int

    /** 摘要只在事件排序更晚时覆盖；乱序旧事件照常留痕但不回退摘要。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.lastTrackStatus = :status, s.lastTrackAt = :at, " +
            "s.lastTrackEventId = :eventId, s.lastTrackLocation = :location " +
            "where s.id = :id and " +
            "(s.lastTrackAt is null or s.lastTrackAt < :at or " +
            "(s.lastTrackAt = :at and (s.lastTrackEventId is null or s.lastTrackEventId < :eventId)))",
    )
    fun updateLastTrackIfNewer(
        @Param("id") id: Long,
        @Param("status") status: String,
        @Param("at") at: Instant,
        @Param("eventId") eventId: String,
        @Param("location") location: String?,
    ): Int
    // 条件 UPDATE 是权威幂等与防回退点：只接受单向前进的源状态，重复/乱序事件返回 0 行。
    // clearAutomatically=true 会清空持久化上下文，调用方不得再用旧实体生成 payload 或响应。
    // 与 OrderRepository.markShipped 一致：状态作为显式参数传入，不在接口上设默认值
    // （Kotlin 接口默认值对调用方不可见，会让位置参数错位）。

    /** LABEL_PENDING → LABEL_CREATED；取消竞态下不覆盖 CANCEL_PENDING。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.status = :next, s.trackingNo = :trackingNo, " +
            "s.trackingNoNormalized = :normalized, s.carrierLabelUrl = :labelUrl, " +
            "s.trackingUrl = :trackingUrl " +
            "where s.id = :id and s.status = :expected",
    )
    fun markLabelCreated(
        @Param("id") id: Long,
        @Param("expected") expected: ShipmentStatus,
        @Param("next") next: ShipmentStatus,
        @Param("trackingNo") trackingNo: String,
        @Param("normalized") normalized: String?,
        @Param("labelUrl") labelUrl: String?,
        @Param("trackingUrl") trackingUrl: String?,
    ): Int

    /** LABEL_CREATED → IN_TRANSIT；写 shippedAt。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.status = :next, s.shippedAt = :at " +
            "where s.id = :id and s.status = :expected",
    )
    fun markInTransit(
        @Param("id") id: Long,
        @Param("expected") expected: ShipmentStatus,
        @Param("next") next: ShipmentStatus,
        @Param("at") at: Instant,
    ): Int

    /** IN_TRANSIT → OUT_FOR_DELIVERY。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.status = :next where s.id = :id and s.status = :expected",
    )
    fun markOutForDelivery(
        @Param("id") id: Long,
        @Param("expected") expected: ShipmentStatus,
        @Param("next") next: ShipmentStatus,
    ): Int

    /** IN_TRANSIT / OUT_FOR_DELIVERY → DELIVERED；写 deliveredAt。不接受 CANCELLED 复活。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.status = :next, s.deliveredAt = :at " +
            "where s.id = :id and s.status in :allowed",
    )
    fun markDelivered(
        @Param("id") id: Long,
        @Param("allowed") allowed: List<ShipmentStatus>,
        @Param("next") next: ShipmentStatus,
        @Param("at") at: Instant,
    ): Int

    /** LABEL_PENDING / LABEL_CREATED → CANCEL_PENDING。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.status = :next, s.cancelReason = :reason " +
            "where s.id = :id and s.status in :allowed",
    )
    fun markCancelPending(
        @Param("id") id: Long,
        @Param("allowed") allowed: List<ShipmentStatus>,
        @Param("next") next: ShipmentStatus,
        @Param("reason") reason: String,
    ): Int

    /** CANCEL_PENDING → CANCELLED。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.status = :next " +
            "where s.id = :id and s.status = :expected",
    )
    fun markCancelledFromPending(
        @Param("id") id: Long,
        @Param("expected") expected: ShipmentStatus,
        @Param("next") next: ShipmentStatus,
    ): Int

    /** MANUAL 直达：LABEL_PENDING / LABEL_CREATED → CANCELLED。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Shipment s set s.status = :next, s.cancelReason = :reason " +
            "where s.id = :id and s.status in :allowed",
    )
    fun markCancelledImmediate(
        @Param("id") id: Long,
        @Param("allowed") allowed: List<ShipmentStatus>,
        @Param("next") next: ShipmentStatus,
        @Param("reason") reason: String,
    ): Int
}
