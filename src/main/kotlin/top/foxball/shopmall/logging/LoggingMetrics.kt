package top.foxball.shopmall.logging

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Counter
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/** Low-cardinality metrics for the file and live logging paths. */
@Component
class LoggingMetrics(
    private val meterRegistry: MeterRegistry,
) {
    private val activeFileSize = AtomicLong(0)
    private val liveEvents = AtomicLong(0)
    private val liveBytes = AtomicLong(0)
    private val liveRequests = AtomicLong(0)
    private val bytesWritten: Counter = meterRegistry.counter("shopmall.logging.bytes_written")
    private val fileErrors: Counter = meterRegistry.counter("shopmall.logging.file_errors")
    private val liveEvictions: Counter = meterRegistry.counter("shopmall.logging.live_events_evicted")
    private val eventCounters = LogLevel.entries.associateWith { level ->
        mapOf(
            false to meterRegistry.counter(
                "shopmall.logging.events",
                "level", level.name,
                "outcome", "written",
            ),
            true to meterRegistry.counter(
                "shopmall.logging.events",
                "level", level.name,
                "outcome", "truncated",
            ),
        )
    }
    private val failedEvents: Counter = meterRegistry.counter(
        "shopmall.logging.events",
        "level", "unknown",
        "outcome", "failed",
    )
    private val rotationCounters = listOf("startup", "recovery", "date", "format", "size", "other")
        .associateWith { reason -> meterRegistry.counter("shopmall.logging.rotations", "reason", reason) }
    private val settingsCounters = listOf("success", "conflict", "other")
        .associateWith { outcome -> meterRegistry.counter("shopmall.logging.settings_updates", "outcome", outcome) }
    private val historyCounters = listOf("success", "not_found", "other")
        .associateWith { outcome -> meterRegistry.counter("shopmall.logging.history_reads", "outcome", outcome) }
    private val droppedCounters = AsyncLogStage.entries.flatMap { stage ->
        AsyncLogDropReason.entries.map { reason ->
            (stage to reason) to meterRegistry.counter(
                "shopmall.logging.async_events_dropped",
                "stage", stage.tag,
                "reason", reason.tag,
            )
        }
    }.toMap()
    private val processingFailureCounters = AsyncLogStage.entries.associateWith { stage ->
        meterRegistry.counter("shopmall.logging.async_processing_errors", "stage", stage.tag)
    }

    init {
        meterRegistry.gauge("shopmall.logging.active_file_size_bytes", activeFileSize)
        meterRegistry.gauge("shopmall.logging.live_buffer_events", liveEvents)
        meterRegistry.gauge("shopmall.logging.live_buffer_bytes", liveBytes)
        meterRegistry.gauge("shopmall.logging.live_requests_active", liveRequests)
    }

    fun written(level: LogLevel, bytes: Int, truncated: Boolean) {
        eventCounters.getValue(level).getValue(truncated).increment()
        bytesWritten.increment(bytes.toDouble())
    }

    fun writeFailed() {
        failedEvents.increment()
        fileErrors.increment()
    }

    fun rotation(reason: String) {
        rotationCounters[reason]?.increment() ?: rotationCounters.getValue("other").increment()
    }

    fun liveEvicted(count: Int = 1) {
        liveEvictions.increment(count.toDouble())
    }

    fun settingsUpdated(outcome: String) {
        settingsCounters[outcome]?.increment() ?: settingsCounters.getValue("other").increment()
    }

    fun historyRead(outcome: String) {
        historyCounters[outcome]?.increment() ?: historyCounters.getValue("other").increment()
    }

    internal fun asyncDropped(stage: AsyncLogStage, result: LogQueueOfferResult, count: Int = 1) {
        if (count <= 0) return
        val reason = when (result) {
            LogQueueOfferResult.ACCEPTED -> return
            LogQueueOfferResult.CLOSED -> AsyncLogDropReason.CLOSED
            LogQueueOfferResult.EVENT_CAPACITY -> AsyncLogDropReason.EVENT_CAPACITY
            LogQueueOfferResult.BYTE_CAPACITY -> AsyncLogDropReason.BYTE_CAPACITY
        }
        droppedCounters.getValue(stage to reason).increment(count.toDouble())
    }

    internal fun asyncProcessingFailed(stage: AsyncLogStage) {
        processingFailureCounters.getValue(stage).increment()
    }

    fun activeFileSize(value: Long) {
        activeFileSize.set(value)
    }

    fun liveBuffer(events: Int, bytes: Long) {
        liveEvents.set(events.toLong())
        liveBytes.set(bytes)
    }

    fun liveRequestStarted() {
        liveRequests.incrementAndGet()
    }

    fun liveRequestFinished() {
        liveRequests.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }
}

internal enum class AsyncLogStage(val tag: String) {
    INPUT("input"),
    FILE("file"),
}

private enum class AsyncLogDropReason(val tag: String) {
    CLOSED("closed"),
    EVENT_CAPACITY("event_capacity"),
    BYTE_CAPACITY("byte_capacity"),
}
