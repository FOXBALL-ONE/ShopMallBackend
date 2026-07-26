package top.foxball.shopmall.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.handler.CarrierException
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
                    if (isTrackingNoInvalid(ex)) {
                        // 单号无效/不存在等不可恢复错误：停止该运单轮询，避免污染退避队列。
                        pollingCoordinator.disable(shipmentId, owner, "TRACKING_NO_INVALID: ${ex.message}")
                    } else {
                        // 临时故障（CarrierException 服务异常或未知异常）继续退避重试。
                        pollingCoordinator.completeFailure(shipmentId, owner, ex)
                    }
                }
        }
    }

    // 不可恢复判定：仅 CarrierException 且 message 命中「单号无效/不存在」关键词；其余异常按临时故障处理。
    private fun isTrackingNoInvalid(ex: Throwable): Boolean {
        if (ex !is CarrierException) return false
        val msg = ex.message?.lowercase() ?: return false
        return INVALID_TRACKING_NO_KEYWORDS.any { msg.contains(it) }
    }

    private companion object {
        val INVALID_TRACKING_NO_KEYWORDS = listOf("not found", "invalid", "不存在", "单号无效")
    }
}
