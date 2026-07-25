package top.foxball.shopmall.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "shopmall.logistics")
class LogisticsProperties {
    var webhookMaxBodyBytes: Int = 1_048_576
    var pollInitialDelaySeconds: Long = 900
    var pollMaxDelaySeconds: Long = 21_600
    var pollLeaseSeconds: Long = 120
    var trackMaxConsecutiveFailures: Int = 5
    var staleDeliveryDays: Long = 30
    var rawTrackRetentionDays: Long = 30
    var pollSchedulerDelayMs: Long = 60_000
    var reconciliationDelayMs: Long = 60_000
    var carriers: MutableMap<String, CarrierProperties> = mutableMapOf()

    @PostConstruct
    fun validate() {
        require(webhookMaxBodyBytes > 0) { "shopmall.logistics.webhook-max-body-bytes must be positive" }
        require(pollInitialDelaySeconds > 0) { "shopmall.logistics.poll-initial-delay-seconds must be positive" }
        require(pollMaxDelaySeconds >= pollInitialDelaySeconds) {
            "shopmall.logistics.poll-max-delay-seconds must be >= poll-initial-delay-seconds"
        }
        carriers.filterValues { it.enabled }.forEach { (carrier, properties) ->
            require(properties.webhookSecret.isNotBlank()) {
                "Enabled carrier $carrier requires webhook-secret"
            }
            require(properties.apiKey.isNotBlank()) { "Enabled carrier $carrier requires api-key" }
        }
    }

    class CarrierProperties {
        var enabled: Boolean = false
        var webhookSecret: String = ""
        var apiKey: String = ""
    }
}
