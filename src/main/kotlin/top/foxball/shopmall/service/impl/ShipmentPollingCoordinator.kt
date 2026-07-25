package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.repository.ShipmentPollingRepository
import top.foxball.shopmall.repository.ShipmentRepository
import java.time.Clock
import java.time.Duration

data class PollingShipment(
    val id: Long,
    val shipmentNo: String,
    val carrierCode: top.foxball.shopmall.entity.jdbc.CarrierCode,
    val trackingNo: String,
    val shippedAt: java.time.Instant?,
)

@Service
class ShipmentPollingCoordinator(
    private val pollingRepository: ShipmentPollingRepository,
    private val shipmentRepository: ShipmentRepository,
    private val properties: LogisticsProperties,
    private val clock: Clock,
) {
    fun claim(owner: String): List<Long> {
        val now = clock.instant()
        return pollingRepository.claimDue(
            now = now,
            leaseOwner = owner,
            leaseUntil = now.plusSeconds(properties.pollLeaseSeconds),
            limit = 50,
        )
    }

    @Transactional(readOnly = true)
    fun load(id: Long, owner: String): PollingShipment? {
        val shipment = shipmentRepository.findById(id).orElse(null) ?: return null
        if (shipment.pollLeaseOwner != owner || shipment.trackingNo == null) return null
        return PollingShipment(
            id = id,
            shipmentNo = shipment.shipmentNo,
            carrierCode = shipment.carrierCode,
            trackingNo = requireNotNull(shipment.trackingNo),
            shippedAt = shipment.shippedAt,
        )
    }

    @Transactional
    fun completeSuccess(id: Long, owner: String) {
        val shipment = shipmentRepository.findByIdForUpdate(id) ?: return
        if (shipment.pollLeaseOwner != owner) return
        shipment.consecutiveTrackFailures = 0
        shipment.lastTrackError = null
        shipment.nextTrackPollAt = if (shipment.status in ACTIVE_STATUSES) {
            clock.instant().plus(successDelay(shipment))
        } else {
            null
        }
        releaseLease(shipment)
    }

    @Transactional
    fun completeFailure(id: Long, owner: String, error: Throwable) {
        val shipment = shipmentRepository.findByIdForUpdate(id) ?: return
        if (shipment.pollLeaseOwner != owner) return
        shipment.consecutiveTrackFailures += 1
        shipment.lastTrackError = error.javaClass.simpleName.take(500)
        shipment.nextTrackPollAt = clock.instant().plus(failureDelay(shipment.consecutiveTrackFailures))
        releaseLease(shipment)
    }

    @Transactional
    fun disable(id: Long, owner: String, reason: String) {
        val shipment = shipmentRepository.findByIdForUpdate(id) ?: return
        if (shipment.pollLeaseOwner != owner) return
        shipment.nextTrackPollAt = null
        shipment.lastTrackError = reason.take(500)
        releaseLease(shipment)
    }

    private fun successDelay(shipment: Shipment): Duration {
        val ageDays = shipment.shippedAt
            ?.let { Duration.between(it, clock.instant()).toDays().coerceAtLeast(0) }
            ?: 0
        return exponentialDelay(ageDays.toInt())
    }

    private fun failureDelay(failures: Int): Duration = exponentialDelay(failures.coerceAtLeast(1))

    private fun exponentialDelay(exponent: Int): Duration {
        val multiplier = 1L shl exponent.coerceIn(0, 16)
        val seconds = (properties.pollInitialDelaySeconds * multiplier)
            .coerceAtMost(properties.pollMaxDelaySeconds)
        return Duration.ofSeconds(seconds)
    }

    private fun releaseLease(shipment: Shipment) {
        shipment.pollLeaseOwner = null
        shipment.pollLeaseUntil = null
    }

    private companion object {
        val ACTIVE_STATUSES = setOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY)
    }
}
