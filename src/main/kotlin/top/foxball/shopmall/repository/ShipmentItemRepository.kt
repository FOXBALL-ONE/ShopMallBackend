package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.AllocationStatus
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import java.time.Instant

interface ShipmentItemRepository : JpaRepository<ShipmentItem, Long> {
    fun findAllByShipment_IdOrderById(shipmentId: Long): List<ShipmentItem>

    @Query(
        "select count(i) from ShipmentItem i where i.orderItemId in :ids " +
            "and i.allocationStatus = :status",
    )
    fun countActiveAllocations(
        @Param("ids") ids: Collection<Long>,
        @Param("status") status: AllocationStatus = AllocationStatus.ALLOCATED,
    ): Long

    @Modifying(flushAutomatically = true)
    @Query(
        "update ShipmentItem i set i.allocationStatus = :released, i.releasedAt = :at, " +
            "i.releaseReason = :reason where i.shipment.id = :shipmentId and i.allocationStatus = :allocated",
    )
    fun releaseAllocatedByShipmentId(
        @Param("shipmentId") shipmentId: Long,
        @Param("at") at: Instant,
        @Param("reason") reason: String,
        @Param("allocated") allocated: AllocationStatus = AllocationStatus.ALLOCATED,
        @Param("released") released: AllocationStatus = AllocationStatus.RELEASED,
    ): Int
}
