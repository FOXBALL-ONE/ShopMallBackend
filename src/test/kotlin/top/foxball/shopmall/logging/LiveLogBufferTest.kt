package top.foxball.shopmall.logging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.springframework.util.unit.DataSize
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveLogBufferTest {
    private fun append(
        buffer: LiveLogBuffer,
        index: Int,
        level: LogLevel = LogLevel.INFO,
        logger: String = "top.foxball.shopmall.service",
    ) {
        appendText(buffer, "message-$index", level, logger)
    }

    private fun appendText(
        buffer: LiveLogBuffer,
        text: String,
        level: LogLevel = LogLevel.INFO,
        logger: String = "top.foxball.shopmall.service",
    ) {
        buffer.append(
            timestamp = LocalDateTime.parse("2026-08-08T19:25:14"),
            level = level,
            logger = logger,
            thread = "test",
            requestId = null,
            rendered = RenderedLogRecord(text, "$text\n".toByteArray(), false),
            templateVersion = 1,
        )
    }

    private fun poll(buffer: LiveLogBuffer, query: LiveLogQuery): LiveLogBatch = runBlocking {
        buffer.poll(query)
    }

    @Test
    fun `first tail starts at the latest limit events and advances to latest sequence`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        repeat(5) { index -> append(buffer, index) }

        val batch = poll(buffer, LiveLogQuery(null, null, LogLevel.TRACE, null, null, 2, 0))

        assertEquals(listOf(4L, 5L), batch.events.map { it.sequence })
        assertEquals(1L, batch.earliestSequence)
        assertEquals(5L, batch.nextSequence)
        assertTrue(!batch.reset)
        assertTrue(!batch.gap)
    }

    @Test
    fun `wrong boot id resets to the current tail and ignores stale cursor`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        repeat(3) { index -> append(buffer, index) }

        val batch = poll(buffer, LiveLogQuery("old-boot", 0, LogLevel.TRACE, null, null, 2, 0))

        assertTrue(batch.reset)
        assertTrue(!batch.gap)
        assertEquals(listOf(2L, 3L), batch.events.map { it.sequence })
        assertEquals(3L, batch.nextSequence)
        assertEquals(1L, batch.earliestSequence)
    }

    @Test
    fun `first tail does not report a gap when retained history starts after sequence one`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        repeat(102) { index ->
            val text = "message-$index"
            buffer.append(
                timestamp = LocalDateTime.parse("2026-08-08T19:25:14"),
                level = LogLevel.INFO,
                logger = "top.foxball.shopmall.service",
                thread = "test",
                requestId = null,
                rendered = RenderedLogRecord(text, "$text\n".toByteArray(), false),
                templateVersion = 1,
            )
        }

        val batch = poll(buffer, LiveLogQuery(null, null, LogLevel.TRACE, null, null, 20, 0))

        assertTrue(!batch.gap)
        assertEquals(20, batch.events.size)
    }

    @Test
    fun `overflow reports a cursor gap and applies filters`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        repeat(102) { index ->
            val text = "message-$index"
            buffer.append(
                timestamp = LocalDateTime.parse("2026-08-08T19:25:14"),
                level = if (index % 2 == 0) LogLevel.INFO else LogLevel.ERROR,
                logger = if (index % 2 == 0) "top.foxball.shopmall.service" else "org.example",
                thread = "test",
                requestId = null,
                rendered = RenderedLogRecord(text, "$text\n".toByteArray(), false),
                templateVersion = 1,
            )
        }

        val batch = poll(buffer,
            LiveLogQuery(
                bootId = null,
                afterSequence = 0,
                minimumLevel = LogLevel.ERROR,
                loggerPrefix = "org.",
                query = "message",
                limit = 20,
                waitSeconds = 0,
            ),
        )

        assertTrue(batch.gap)
        assertEquals(20, batch.events.size)
        assertTrue(batch.events.all { it.level == LogLevel.ERROR })
        assertTrue(batch.events.all { it.logger.startsWith("org.") })
        assertEquals(42L, batch.nextSequence)
    }

    @Test
    fun `a cursor exactly at the retained boundary is not a gap`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        repeat(102) { index -> append(buffer, index) }

        val batch = poll(buffer, LiveLogQuery(null, 2, LogLevel.TRACE, null, null, 20, 0))

        assertTrue(!batch.gap)
        assertEquals(3L, batch.events.first().sequence)
        assertEquals(22L, batch.events.last().sequence)
        assertEquals(22L, batch.nextSequence)
    }

    @Test
    fun `filters advance the cursor across nonmatching events`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        append(buffer, 0, LogLevel.INFO, "top.foxball.shopmall.service")
        append(buffer, 1, LogLevel.ERROR, "org.example")
        append(buffer, 2, LogLevel.INFO, "top.foxball.shopmall.service")
        append(buffer, 3, LogLevel.ERROR, "org.example")

        val first = poll(buffer,
            LiveLogQuery(null, 0, LogLevel.ERROR, "org.", null, 1, 0),
        )
        val second = poll(buffer,
            LiveLogQuery(first.bootId, first.nextSequence, LogLevel.ERROR, "org.", null, 1, 0),
        )

        assertEquals(listOf(2L), first.events.map { it.sequence })
        assertEquals(2L, first.nextSequence)
        assertEquals(listOf(4L), second.events.map { it.sequence })
        assertEquals(4L, second.nextSequence)
    }

    @Test
    fun `response byte cap preserves cursor and still returns one oversized event`() {
        val responseBytes = 1_500L
        val properties = LoggingProperties(
            maxRecordSize = DataSize.ofKilobytes(1),
            liveBufferEvents = 100,
            liveBufferBytes = 256 * 1024,
            liveResponseBytes = responseBytes,
        )
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        appendText(buffer, "a".repeat(550))
        appendText(buffer, "b".repeat(550))
        appendText(buffer, "c".repeat(800))

        val first = poll(buffer, LiveLogQuery(null, 0, LogLevel.TRACE, null, null, 10, 0))
        assertEquals(listOf(1L), first.events.map { it.sequence })
        assertTrue(first.events.sumOf { it.retainedBytes } <= responseBytes)
        assertEquals(1L, first.nextSequence)

        val second = poll(buffer, LiveLogQuery(first.bootId, first.nextSequence, LogLevel.TRACE, null, null, 10, 0))
        assertEquals(listOf(2L), second.events.map { it.sequence })
        assertTrue(second.events.sumOf { it.retainedBytes } <= responseBytes)
        assertEquals(2L, second.nextSequence)

        val oversized = poll(buffer, LiveLogQuery(first.bootId, second.nextSequence, LogLevel.TRACE, null, null, 10, 0))
        assertEquals(listOf(3L), oversized.events.map { it.sequence })
        assertTrue(oversized.events.single().retainedBytes > responseBytes)
        assertEquals(3L, oversized.nextSequence)

        val empty = poll(buffer, LiveLogQuery(first.bootId, oversized.nextSequence, LogLevel.TRACE, null, null, 10, 0))
        assertTrue(empty.events.isEmpty())
        assertEquals(3L, empty.nextSequence)
    }

    @Test
    fun `empty buffer returns the initial cursor without a gap`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))

        val batch = poll(buffer, LiveLogQuery(null, 0, LogLevel.TRACE, null, null, 20, 0))

        assertTrue(batch.events.isEmpty())
        assertTrue(!batch.gap)
        assertEquals(1L, batch.earliestSequence)
        assertEquals(0L, batch.nextSequence)
    }

    @Test
    fun `concurrent appenders retain cursor order`() {
        val eventCount = 500
        val properties = LoggingProperties(liveBufferEvents = eventCount, liveBufferBytes = 4L * 1024 * 1024)
        val buffer = LiveLogBuffer(properties, LoggingMetrics(SimpleMeterRegistry()))
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(eventCount) { index ->
            executor.submit {
                start.await()
                val text = "message-$index"
                buffer.append(
                    timestamp = LocalDateTime.parse("2026-08-08T19:25:14"),
                    level = LogLevel.INFO,
                    logger = "top.foxball.shopmall.test",
                    thread = "worker",
                    requestId = null,
                    rendered = RenderedLogRecord(text, "$text\n".toByteArray(), false),
                    templateVersion = 1,
                )
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        val events = poll(buffer, LiveLogQuery(null, 0, LogLevel.TRACE, null, null, eventCount, 0)).events
        assertEquals(eventCount, events.size)
        assertEquals((1L..eventCount.toLong()).toList(), events.map { it.sequence })
    }
}
