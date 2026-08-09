package top.foxball.shopmall.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.util.unit.DataSize
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShopMallLogAppenderTest {
    @Test
    fun `application thread never waits for deferred message formatting and overload drops instead of blocking`() {
        val registry = SimpleMeterRegistry()
        val properties = properties(asyncQueueEvents = 2)
        val formatterStarted = CountDownLatch(1)
        val releaseFormatter = CountDownLatch(1)
        val blockingArgument = object {
            override fun toString(): String {
                formatterStarted.countDown()
                releaseFormatter.await()
                return "formatted"
            }
        }
        val appender = appender(NoOpLogFileSink(), properties, registry)
        appender.start()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                appender.doAppend(event("message={}", arrayOf(blockingArgument)))
            }
            assertTrue(formatterStarted.await(2, TimeUnit.SECONDS))

            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                repeat(2_000) { index -> appender.doAppend(event("queued-$index")) }
            }

            val dropped = registry.get("shopmall.logging.async_events_dropped")
                .tags("stage", "input", "reason", "event_capacity")
                .counter()
                .count()
            assertTrue(dropped > 0.0)
        } finally {
            releaseFormatter.countDown()
            appender.stop()
        }
    }

    @Test
    fun `blocked file sink does not stop live publication and shutdown remains bounded`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val properties = properties(fileQueueEvents = 1, fileBatchEvents = 1, shutdownTimeoutMillis = 100)
        val sink = BlockingLogFileSink()
        val liveBuffer = LiveLogBuffer(properties, LoggingMetrics(registry))
        val appender = appender(sink, properties, registry, liveBuffer)
        appender.start()

        try {
            appender.doAppend(event("first"))
            assertTrue(sink.writeStarted.await(2, TimeUnit.SECONDS))

            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                repeat(500) { index -> appender.doAppend(event("after-disk-block-$index")) }
            }
            val live = liveBuffer.poll(
                LiveLogQuery(
                    bootId = null,
                    afterSequence = null,
                    minimumLevel = LogLevel.TRACE,
                    loggerPrefix = null,
                    query = "after-disk-block",
                    limit = 1,
                    waitSeconds = 1,
                ),
            )
            assertEquals(1, live.events.size)

            val dropped = registry.get("shopmall.logging.async_events_dropped")
                .tags("stage", "file", "reason", "event_capacity")
                .counter()
                .count()
            assertTrue(dropped > 0.0)

            assertTimeoutPreemptively(Duration.ofSeconds(1)) { appender.stop() }
        } finally {
            sink.release.countDown()
            sink.closed.await(2, TimeUnit.SECONDS)
            appender.stop()
        }
    }

    @Test
    fun `application capture does not invoke custom collection size`() {
        val registry = SimpleMeterRegistry()
        val properties = properties()
        val sink = SignallingLogFileSink()
        val sizeCalls = AtomicInteger()
        val argument = object : Collection<Any> {
            override val size: Int
                get() {
                    sizeCalls.incrementAndGet()
                    return 0
                }

            override fun contains(element: Any): Boolean = false

            override fun containsAll(elements: Collection<Any>): Boolean = elements.isEmpty()

            override fun isEmpty(): Boolean = true

            override fun iterator(): Iterator<Any> = emptyList<Any>().iterator()

            override fun toString(): String = "safe-collection"
        }
        val appender = appender(sink, properties, registry)
        appender.start()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                appender.doAppend(event("value={}", arrayOf<Any>(argument)))
            }
            assertTrue(sink.written.await(2, TimeUnit.SECONDS))
            assertEquals(0, sizeCalls.get())
        } finally {
            appender.stop()
        }
    }

    @Test
    fun `oversized argument arrays are dropped without application formatting`() {
        val registry = SimpleMeterRegistry()
        val properties = properties()
        val formatted = AtomicInteger()
        val argument = object {
            override fun toString(): String {
                formatted.incrementAndGet()
                return "should-not-be-formatted"
            }
        }
        val appender = appender(NoOpLogFileSink(), properties, registry)
        appender.start()

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                appender.doAppend(event("values={}", Array(1_000) { argument }))
            }
            val dropped = registry.get("shopmall.logging.async_events_dropped")
                .tags("stage", "input", "reason", "byte_capacity")
                .counter()
                .count()
            assertEquals(1.0, dropped)
            assertEquals(0, formatted.get())
        } finally {
            appender.stop()
        }
    }

    private fun appender(
        sink: LogFileSink,
        properties: LoggingProperties,
        registry: SimpleMeterRegistry,
        liveBuffer: LiveLogBuffer = LiveLogBuffer(properties, LoggingMetrics(registry)),
    ): ShopMallLogAppender = ShopMallLogAppender(
        writer = sink,
        liveBuffer = liveBuffer,
        properties = properties,
        metrics = LoggingMetrics(registry),
        initialFormatter = RuntimeLogFormatter.compile(LoggingProperties.DEFAULT_OUTPUT_TEMPLATE),
        initialTemplateVersion = 0,
    ).apply {
        context = LoggerContext()
    }

    private fun properties(
        asyncQueueEvents: Int = 32,
        fileQueueEvents: Int = 32,
        fileBatchEvents: Int = 8,
        shutdownTimeoutMillis: Long = 500,
    ) = LoggingProperties(
        maxRecordSize = DataSize.ofKilobytes(1),
        liveBufferEvents = 100,
        liveBufferBytes = 1L * 1024 * 1024,
        liveResponseBytes = 1L * 1024 * 1024,
        asyncQueueEvents = asyncQueueEvents,
        asyncQueueBytes = 1L * 1024 * 1024,
        fileQueueEvents = fileQueueEvents,
        fileQueueBytes = 1L * 1024 * 1024,
        fileBatchEvents = fileBatchEvents,
        fileFlushIntervalMillis = 10,
        fileFailureBackoffMillis = 10,
        shutdownTimeoutMillis = shutdownTimeoutMillis,
    )

    private fun event(message: String, arguments: Array<Any>? = null): LoggingEvent = LoggingEvent().apply {
        timeStamp = System.currentTimeMillis()
        level = Level.INFO
        loggerName = "top.foxball.shopmall.Test"
        threadName = Thread.currentThread().name
        this.message = message
        argumentArray = arguments
        mdcPropertyMap = emptyMap()
    }

    private class NoOpLogFileSink : LogFileSink {
        override fun writeBatch(records: List<FileLogRecord>) = FileBatchWriteResult(true, records.size, 0)

        override fun close() = Unit
    }

    private class SignallingLogFileSink : LogFileSink {
        val written = CountDownLatch(1)

        override fun writeBatch(records: List<FileLogRecord>): FileBatchWriteResult {
            written.countDown()
            return FileBatchWriteResult(true, records.size, 0)
        }

        override fun close() = Unit
    }

    private class BlockingLogFileSink : LogFileSink {
        val writeStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun writeBatch(records: List<FileLogRecord>): FileBatchWriteResult {
            writeStarted.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Simulate a sink that cannot be cancelled while the storage device is stuck.
                }
            }
            return FileBatchWriteResult(true, records.size, 0)
        }

        override fun close() {
            closed.countDown()
        }
    }
}
