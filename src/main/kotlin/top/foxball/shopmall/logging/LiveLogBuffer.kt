package top.foxball.shopmall.logging

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Local-only bounded tail buffer used by the authenticated asynchronous long-poll endpoint. */
@Component
class LiveLogBuffer(
    private val properties: LoggingProperties,
    private val metrics: LoggingMetrics,
) {
    private val lock = ReentrantLock()
    private val bootId = UUID.randomUUID().toString()
    private val sequenceSignal = MutableStateFlow(0L)
    private var sequence = 0L
    private val events = TreeMap<Long, LiveLogEvent>()
    private var retainedBytes = 0L
    private var evictedCount = 0L

    fun append(
        timestamp: LocalDateTime,
        level: LogLevel,
        logger: String,
        thread: String,
        requestId: String?,
        rendered: RenderedLogRecord,
        templateVersion: Long,
    ) {
        val renderedTextBytes = rendered.text.length.toLong() * LoggingProperties.ESTIMATED_CHARACTER_BYTES
        val retained = maxOf(rendered.bytes.size.toLong(), renderedTextBytes) +
            (logger.length + thread.length + requestId.orEmpty().length).toLong() *
            LoggingProperties.ESTIMATED_CHARACTER_BYTES +
            EVENT_OVERHEAD_BYTES
        val appendResult = lock.withLock {
            val nextSequence = ++sequence
            events[nextSequence] = LiveLogEvent(
                sequence = nextSequence,
                timestamp = timestamp,
                level = level,
                logger = logger,
                thread = thread,
                requestId = requestId,
                rendered = rendered.text,
                templateVersion = templateVersion,
                retainedBytes = retained,
            )
            retainedBytes += retained
            var evicted = 0
            while (events.size > properties.liveBufferEvents || retainedBytes > properties.liveBufferBytes) {
                val removed = events.pollFirstEntry().value
                retainedBytes -= removed.retainedBytes
                evictedCount++
                evicted++
            }
            AppendResult(nextSequence, evicted, events.size, retainedBytes)
        }
        if (appendResult.evicted > 0) metrics.liveEvicted(appendResult.evicted)
        metrics.liveBuffer(appendResult.events, appendResult.bytes)
        sequenceSignal.update { current -> maxOf(current, appendResult.sequence) }
    }

    suspend fun poll(query: LiveLogQuery): LiveLogBatch {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(query.waitSeconds.toLong())
        val initialMetadata = metadata()
        val reset = query.bootId != null && query.bootId != bootId
        val initialTail = query.afterSequence == null && !reset
        val requestedAfter = if (reset || query.afterSequence == null) {
            (initialMetadata.latestSequence - query.limit).coerceAtLeast(0)
        } else {
            query.afterSequence
        }
        val originalRequestedAfter = requestedAfter
        val loggerPrefix = query.loggerPrefix?.trim().orEmpty()
        val needle = query.query?.trim().orEmpty()
        val matched = ArrayList<LiveLogEvent>(query.limit)
        var matchedBytes = 0L
        var scanAfter = requestedAfter
        var scanThrough = initialMetadata.latestSequence
        var gap = false
        var droppedCount = 0L
        var forceReturn = reset || query.waitSeconds == 0
        var firstMatchDeadlineNanos: Long? = null

        while (true) {
            val chunk = snapshotChunk(scanAfter, scanThrough)
            if (scanAfter < chunk.earliestSequence - 1) {
                if (!reset && !initialTail) {
                    gap = true
                    droppedCount = (chunk.earliestSequence - 1 - originalRequestedAfter).coerceAtLeast(0)
                    forceReturn = true
                }
                scanAfter = chunk.earliestSequence - 1
                continue
            }

            var responseLimitReached = false
            for (event in chunk.events) {
                val accepted = event.level.severity >= query.minimumLevel.severity &&
                    event.logger.startsWith(loggerPrefix) &&
                    (needle.isEmpty() || event.rendered.contains(needle, ignoreCase = true))
                if (accepted) {
                    if (matched.isNotEmpty() && matchedBytes + event.retainedBytes > properties.liveResponseBytes) {
                        responseLimitReached = true
                        break
                    }
                    matched += event
                    matchedBytes += event.retainedBytes
                    if (firstMatchDeadlineNanos == null) {
                        firstMatchDeadlineNanos = System.nanoTime() +
                            TimeUnit.MILLISECONDS.toNanos(properties.liveBatchWindowMillis)
                    }
                }
                scanAfter = event.sequence
                if (matched.size == query.limit) {
                    return finishBatch(
                        reset = reset,
                        gap = gap,
                        droppedCount = droppedCount,
                        nextSequence = scanAfter,
                        events = matched,
                        firstMatchDeadlineNanos = firstMatchDeadlineNanos,
                        requestDeadlineNanos = deadlineNanos,
                        forceReturn = forceReturn,
                    )
                }
            }
            if (responseLimitReached) {
                return finishBatch(
                    reset = reset,
                    gap = gap,
                    droppedCount = droppedCount,
                    nextSequence = scanAfter,
                    events = matched,
                    firstMatchDeadlineNanos = firstMatchDeadlineNanos,
                    requestDeadlineNanos = deadlineNanos,
                    forceReturn = forceReturn,
                )
            }
            if (scanAfter < chunk.latestSequence) continue
            if (forceReturn) return batch(reset, gap, droppedCount, scanAfter, matched)

            val now = System.nanoTime()
            val returnDeadline = firstMatchDeadlineNanos?.let { minOf(it, deadlineNanos) } ?: deadlineNanos
            if (now >= returnDeadline) return batch(reset, gap, droppedCount, scanAfter, matched)
            val waitNanos = returnDeadline - now
            val waitMillis = TimeUnit.NANOSECONDS.toMillis(waitNanos).coerceAtLeast(1)
            val changed = withTimeoutOrNull(waitMillis) {
                sequenceSignal.first { latest -> latest > scanAfter }
            } != null
            if (!changed) return batch(reset, gap, droppedCount, scanAfter, matched)
            scanThrough = sequenceSignal.value
        }
    }

    private fun snapshotChunk(afterSequence: Long, throughSequence: Long): BufferChunk = lock.withLock {
        val latest = minOf(sequence, throughSequence)
        val earliest = events.firstEntry()?.key ?: latest + 1
        BufferChunk(
            earliestSequence = earliest,
            latestSequence = latest,
            events = events.tailMap(afterSequence, false).values
                .asSequence()
                .takeWhile { event -> event.sequence <= latest }
                .take(SCAN_CHUNK_EVENTS)
                .toList(),
        )
    }

    private fun metadata(): BufferMetadata = lock.withLock {
        BufferMetadata(
            earliestSequence = events.firstEntry()?.key ?: sequence + 1,
            latestSequence = sequence,
        )
    }

    private fun batch(
        reset: Boolean,
        gap: Boolean,
        droppedCount: Long,
        nextSequence: Long,
        events: List<LiveLogEvent>,
    ): LiveLogBatch {
        val current = metadata()
        return LiveLogBatch(
            bootId = bootId,
            reset = reset,
            gap = gap,
            droppedCount = droppedCount,
            earliestSequence = current.earliestSequence,
            nextSequence = nextSequence.coerceAtMost(current.latestSequence),
            events = events,
        )
    }

    /** Coalesces full responses without occupying a servlet thread or delaying reset/gap notices. */
    private suspend fun finishBatch(
        reset: Boolean,
        gap: Boolean,
        droppedCount: Long,
        nextSequence: Long,
        events: List<LiveLogEvent>,
        firstMatchDeadlineNanos: Long?,
        requestDeadlineNanos: Long,
        forceReturn: Boolean,
    ): LiveLogBatch {
        if (!forceReturn && firstMatchDeadlineNanos != null) {
            val remainingNanos = minOf(firstMatchDeadlineNanos, requestDeadlineNanos) - System.nanoTime()
            if (remainingNanos > 0) {
                delay(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1))
            }
        }
        return batch(reset, gap, droppedCount, nextSequence, events)
    }

    private data class AppendResult(
        val sequence: Long,
        val evicted: Int,
        val events: Int,
        val bytes: Long,
    )

    private data class BufferMetadata(
        val earliestSequence: Long,
        val latestSequence: Long,
    )

    private data class BufferChunk(
        val earliestSequence: Long,
        val latestSequence: Long,
        val events: List<LiveLogEvent>,
    )

    private companion object {
        const val EVENT_OVERHEAD_BYTES = 128L
        const val SCAN_CHUNK_EVENTS = 256
    }
}
