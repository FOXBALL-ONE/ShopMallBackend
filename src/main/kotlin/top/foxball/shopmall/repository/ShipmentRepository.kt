package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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
}
