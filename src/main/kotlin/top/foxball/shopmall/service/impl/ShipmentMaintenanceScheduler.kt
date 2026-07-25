package top.foxball.shopmall.service.impl

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.repository.ShipmentPollingRepository
import java.time.Clock
import java.time.Duration

@Component
class ShipmentMaintenanceScheduler(
    private val pollingRepository: ShipmentPollingRepository,
    private val properties: LogisticsProperties,
    private val clock: Clock,
) {
    @Scheduled(cron = "0 45 3 * * *")
    fun clearExpiredRawPayloads() {
        pollingRepository.clearExpiredRawPayloads(
            clock.instant().minus(Duration.ofDays(properties.rawTrackRetentionDays)),
        )
    }
}
