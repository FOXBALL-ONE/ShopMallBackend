package top.foxball.shopmall.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.logistics.CarrierRegistry
import top.foxball.shopmall.service.ShipmentService
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Component
@ConditionalOnProperty(
    prefix = "shopmall.logistics",
    name = ["polling-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ShipmentTrackingScheduler(
    private val pollingCoordinator: ShipmentPollingCoordinator,
    private val carrierRegistry: CarrierRegistry,
    private val shipmentService: ShipmentService,
    private val properties: LogisticsProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val owner = "poller-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${shopmall.logistics.poll-scheduler-delay-ms:60000}")
    fun poll() {
        pollingCoordinator.claim(owner).forEach { shipmentId ->
            val shipment = pollingCoordinator.load(shipmentId, owner) ?: return@forEach
            val carrier = carrierRegistry.find(shipment.carrierCode)
            if (carrier == null || !carrier.capabilities.polling) {
                pollingCoordinator.disable(shipmentId, owner, "POLLING_ADAPTER_UNAVAILABLE")
                return@forEach
            }
            if (
                shipment.shippedAt != null &&
                Duration.between(shipment.shippedAt, clock.instant()).toDays() >= properties.staleDeliveryDays
            ) {
                log.warn("Shipment {} has exceeded the delivery SLA", shipment.shipmentNo)
            }
            runCatching { carrier.queryTracking(shipment.trackingNo) }
                .onSuccess { events ->
                    events.sortedWith(compareBy({ it.occurredAt }, { it.carrierEventId })).forEach {
                        shipmentService.handleTrackingEvent(shipment.carrierCode, it, TrackSource.POLL)
                    }
                    pollingCoordinator.completeSuccess(shipmentId, owner)
                }
                .onFailure { ex ->
                    log.warn(
                        "Tracking poll failed for shipment {}: {}",
                        shipment.shipmentNo,
                        ex::class.simpleName ?: "TrackingException",
                    )
                    pollingCoordinator.completeFailure(shipmentId, owner, ex)
                }
        }
    }
}
