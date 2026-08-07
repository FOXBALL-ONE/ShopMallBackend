package top.foxball.shopmall.ratelimit

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** Emits low-cardinality operational metrics for the global rate limiter. */
@Component
class RateLimitMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun request(identity: RateLimitIdentityType, allowed: Boolean) {
        meterRegistry.counter(
            "shopmall.rate_limit.requests",
            "identity", identity.name.lowercase(),
            "outcome", if (allowed) "allowed" else "rejected",
        ).increment()
    }

    fun error() {
        meterRegistry.counter("shopmall.rate_limit.errors").increment()
    }

    fun settingsUpdated() {
        meterRegistry.counter("shopmall.rate_limit.settings_updates").increment()
    }

    fun exclusion(source: String) {
        meterRegistry.counter("shopmall.rate_limit.exclusions", "source", source).increment()
    }
}
