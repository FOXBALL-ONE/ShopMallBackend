package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import top.foxball.shopmall.entity.jdbc.ShipmentTrack
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.logistics.TrackingEvent

interface ShipmentTrackRepository : JpaRepository<ShipmentTrack, Long>, ShipmentTrackInsertRepository {
    fun findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId: Long): List<ShipmentTrack>
}

interface ShipmentTrackInsertRepository {
    fun insertOnConflictDoNothing(shipmentId: Long, event: TrackingEvent, source: TrackSource): Boolean
}

@Repository
class ShipmentTrackInsertRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate,
) : ShipmentTrackInsertRepository {
    override fun insertOnConflictDoNothing(
        shipmentId: Long,
        event: TrackingEvent,
        source: TrackSource,
    ): Boolean {
        val inserted = jdbcTemplate.update(
            """
            INSERT INTO shipment_tracks (
                shipment_id, carrier_event_id, status_code, normalized_status, source,
                location, description, occurred_at, raw, received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (shipment_id, carrier_event_id) DO NOTHING
            """.trimIndent(),
            shipmentId,
            event.carrierEventId,
            event.statusCode,
            event.normalizedStatus.name,
            source.name,
            event.location,
            event.description,
            java.sql.Timestamp.from(event.occurredAt),
            event.raw,
        )
        return inserted == 1
    }
}
