package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.repository.ShipmentPollingRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.service.impl.ShipmentPollingCoordinator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShipmentPollingCoordinatorTest {
    private val pollingRepository = mock(ShipmentPollingRepository::class.java)
    private val shipmentRepository = mock(ShipmentRepository::class.java)
    private val properties = LogisticsProperties().apply {
        pollInitialDelaySeconds = 900
        pollMaxDelaySeconds = 21_600
    }
    private val clock = Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), ZoneOffset.UTC)
    private val coordinator = ShipmentPollingCoordinator(
        pollingRepository,
        shipmentRepository,
        properties,
        clock,
    )

    @Test
    fun `successful poll resets failures schedules next poll and releases lease`() {
        val shipment = shipment()
        shipment.consecutiveTrackFailures = 3
        shipment.lastTrackError = "previous"
        `when`(shipmentRepository.findByIdForUpdate(10)).thenReturn(shipment)

        coordinator.completeSuccess(10, "worker-1")

        assertEquals(0, shipment.consecutiveTrackFailures)
        assertNull(shipment.lastTrackError)
        assertEquals(clock.instant().plusSeconds(900), shipment.nextTrackPollAt)
        assertNull(shipment.pollLeaseOwner)
        assertNull(shipment.pollLeaseUntil)
    }

    @Test
    fun `failed poll records safe error backs off and releases lease`() {
        val shipment = shipment()
        `when`(shipmentRepository.findByIdForUpdate(10)).thenReturn(shipment)

        coordinator.completeFailure(10, "worker-1", IllegalStateException("api-key=secret"))

        assertEquals(1, shipment.consecutiveTrackFailures)
        assertEquals("IllegalStateException", shipment.lastTrackError)
        assertEquals(clock.instant().plusSeconds(1_800), shipment.nextTrackPollAt)
        assertNull(shipment.pollLeaseOwner)
        assertNull(shipment.pollLeaseUntil)
    }

    private fun shipment() = Shipment(
        id = 10,
        shipmentNo = "S-10",
        status = ShipmentStatus.IN_TRANSIT,
        trackingNo = "TRACK-10",
        pollLeaseOwner = "worker-1",
        pollLeaseUntil = clock.instant().plusSeconds(120),
        shippedAt = clock.instant(),
        createdBy = 1,
    )
}
