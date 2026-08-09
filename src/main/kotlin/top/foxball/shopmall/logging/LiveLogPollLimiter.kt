package top.foxball.shopmall.logging

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Keeps long-poll resource use bounded separately from the global request-rate limiter. */
@Component
class LiveLogPollLimiter(
    private val properties: LoggingProperties,
    private val metrics: LoggingMetrics,
) {
    private val total = AtomicInteger(0)
    private val byAdmin = ConcurrentHashMap<Long, AtomicInteger>()

    suspend fun <T> withPermit(adminId: Long, action: suspend () -> T): T {
        val adminCount = synchronized(this) {
            val count = byAdmin.computeIfAbsent(adminId) { AtomicInteger(0) }
            if (count.get() >= properties.liveMaxAdminPolls || total.get() >= properties.liveMaxNodePolls) {
                throw LiveLogPollLimitException()
            }
            count.incrementAndGet()
            total.incrementAndGet()
            count
        }
        metrics.liveRequestStarted()
        try {
            return action()
        } finally {
            synchronized(this) {
                total.decrementAndGet()
                adminCount.decrementAndGet()
                cleanup(adminId, adminCount)
            }
            metrics.liveRequestFinished()
        }
    }

    private fun cleanup(adminId: Long, count: AtomicInteger) {
        if (count.get() <= 0) byAdmin.remove(adminId, count)
    }
}
