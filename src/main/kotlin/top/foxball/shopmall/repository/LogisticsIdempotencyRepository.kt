package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.LogisticsIdempotency

interface LogisticsIdempotencyRepository : JpaRepository<LogisticsIdempotency, Long> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: Long,
        operation: String,
        idempotencyKey: String,
    ): LogisticsIdempotency?

    @Modifying(flushAutomatically = true)
    @Query("delete from LogisticsIdempotency i where i.shipmentId = :shipmentId")
    fun deleteAllByShipmentId(@Param("shipmentId") shipmentId: Long): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from LogisticsIdempotency i where i.shipmentId in :shipmentIds")
    fun deleteAllByShipmentIdIn(@Param("shipmentIds") shipmentIds: Collection<Long>): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from LogisticsIdempotency i where i.actorId in :actorIds")
    fun deleteAllByActorIdIn(@Param("actorIds") actorIds: Collection<Long>): Int
}
