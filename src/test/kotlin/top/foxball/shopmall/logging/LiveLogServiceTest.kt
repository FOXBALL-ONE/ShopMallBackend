package top.foxball.shopmall.logging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import top.foxball.shopmall.service.AdminAccessService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiveLogServiceTest {
    private fun poll(service: LiveLogService, adminId: Long, query: LiveLogQuery): LiveLogBatch = runBlocking {
        service.poll(adminId, query)
    }

    private fun <T> withPermit(limiter: LiveLogPollLimiter, adminId: Long, action: () -> T): T = runBlocking {
        limiter.withPermit(adminId) { action() }
    }

    @Test
    fun `poll requires admin access and accepts the documented boundary values`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val metrics = LoggingMetrics(SimpleMeterRegistry())
        val adminAccess = mock(AdminAccessService::class.java)
        val buffer = LiveLogBuffer(properties, metrics)
        val service = LiveLogService(
            adminAccess,
            buffer,
            LiveLogPollLimiter(properties, metrics),
            properties,
        )

        val batch = poll(service,
            99,
            LiveLogQuery(
                bootId = "b".repeat(64),
                afterSequence = 0,
                minimumLevel = LogLevel.TRACE,
                loggerPrefix = "l".repeat(200),
                query = "q".repeat(LogHistoryService.MAX_QUERY_LENGTH),
                limit = 500,
                waitSeconds = 0,
            ),
        )

        verify(adminAccess).requireAdmin(99)
        assertTrue(batch.events.isEmpty())
        assertEquals(0L, batch.nextSequence)
    }

    @Test
    fun `poll rejects OFF and values outside the live query bounds`() {
        val properties = LoggingProperties(liveBufferEvents = 100, liveBufferBytes = 256 * 1024)
        val metrics = LoggingMetrics(SimpleMeterRegistry())
        val service = LiveLogService(
            mock(AdminAccessService::class.java),
            LiveLogBuffer(properties, metrics),
            LiveLogPollLimiter(properties, metrics),
            properties,
        )

        assertFailsWith<IllegalArgumentException> {
            poll(service, 99, LiveLogQuery(null, null, LogLevel.OFF, null, null, 20, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            poll(service, 99, LiveLogQuery("b".repeat(65), null, LogLevel.TRACE, null, null, 20, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            poll(service, 99, LiveLogQuery(null, -1, LogLevel.TRACE, null, null, 20, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            poll(service, 99, LiveLogQuery(null, null, LogLevel.TRACE, "l".repeat(201), null, 20, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            poll(
                service,
                99,
                LiveLogQuery(
                    null,
                    null,
                    LogLevel.TRACE,
                    null,
                    "q".repeat(LogHistoryService.MAX_QUERY_LENGTH + 1),
                    20,
                    0,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            poll(service, 99, LiveLogQuery(null, null, LogLevel.TRACE, null, null, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            poll(service, 99, LiveLogQuery(null, null, LogLevel.TRACE, null, null, 501, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            poll(
                service,
                99,
                LiveLogQuery(null, null, LogLevel.TRACE, null, null, 20, properties.liveMaxWaitSeconds + 1),
            )
        }
    }

    @Test
    fun `poll limiter releases permits after success rejection and failure`() {
        val properties = LoggingProperties(
            liveBufferEvents = 100,
            liveBufferBytes = 256 * 1024,
            liveMaxNodePolls = 1,
            liveMaxAdminPolls = 1,
        )
        val limiter = LiveLogPollLimiter(properties, LoggingMetrics(SimpleMeterRegistry()))

        val first = withPermit(limiter, 99) {
            assertFailsWith<LiveLogPollLimitException> {
                withPermit(limiter, 99) { "not reached" }
            }
            "completed"
        }
        assertEquals("completed", first)
        assertEquals("released", withPermit(limiter, 99) { "released" })

        assertFailsWith<IllegalStateException> {
            withPermit(limiter, 99) { error("boom") }
        }
        assertEquals("released again", withPermit(limiter, 99) { "released again" })
    }
}
