package top.foxball.shopmall.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import top.foxball.shopmall.service.AdminAccessService
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class ApiRateLimitRedisIntegrationTest {
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redis: StringRedisTemplate
    private lateinit var service: ApiRateLimitService
    private lateinit var settingsService: RateLimitSettingsService

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redisContainer.host, redisContainer.getMappedPort(REDIS_PORT)).apply {
            afterPropertiesSet()
        }
        redis = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
        redis.connectionFactory?.connection?.serverCommands()?.flushDb()
        val properties = RateLimitProperties(identityHashSecret = "a".repeat(32))
        val metrics = RateLimitMetrics(SimpleMeterRegistry())
        service = ApiRateLimitService(
            redis = redis,
            properties = properties,
            metrics = metrics,
        )
        settingsService = RateLimitSettingsService(
            redis = redis,
            properties = properties,
            adminAccessService = org.mockito.Mockito.mock(AdminAccessService::class.java),
            metrics = metrics,
        )
    }

    @Test
    fun `concurrent requests accept exactly the configured authenticated quota`() {
        val executor = Executors.newFixedThreadPool(24)
        try {
            val decisions = executor.invokeAll(
                (1..80).map {
                    Callable {
                        service.decide(RateLimitIdentityType.AUTHENTICATED, "42", 10)
                    }
                },
            ).map { it.get() }

            assertEquals(10, decisions.count { it.allowed })
            assertEquals(70, decisions.count { !it.allowed })
            assertTrue(decisions.filterNot { it.allowed }.all { it.retryAfterSeconds >= 1 })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `anonymous identities are isolated and HMAC key does not contain raw address`() {
        repeat(5) {
            assertTrue(service.decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5).allowed)
        }
        assertFalse(service.decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5).allowed)
        assertTrue(service.decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.10", 5).allowed)

        val keys = redis.keys("rate-limit:v1:anonymous:*")
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.none { it.contains("198.51.100.9") || it.contains("198.51.100.10") })
    }

    @Test
    fun `settings update is versioned and dynamic exclusion matches immediately`() {
        assertEquals(RateLimitSettingsSource.DEFAULT, settingsService.getSettings().source)

        val result = settingsService.updateSettings(
            adminId = 42,
            command = UpdateRateLimitSettingsCommand(
                enabled = false,
                authenticatedRequestsPerMinute = 12,
                anonymousRequestsPerMinute = 6,
                excludedPaths = listOf("/api/files/public", "/api/catalog/**"),
                expectedVersion = 0,
            ),
        )
        val updated = (result as RateLimitSettingsUpdateResult.Updated).settings
        assertEquals(1, updated.version)
        assertFalse(updated.enabled)
        assertEquals("false", redis.opsForHash<String, String>().get("rate-limit:settings:v1", "enabled"))
        assertEquals(listOf("/api/catalog/**", "/api/files/public"), updated.excludedPaths)
        assertEquals(1, settingsService.getSettings().version)
        assertFalse(settingsService.getSettings().enabled)

        val request = MockHttpServletRequest("GET", "/api/catalog/items")
        assertTrue(settingsService.matchesExcludedPath(updated, request))

        val conflict = settingsService.updateSettings(
            adminId = 42,
            command = UpdateRateLimitSettingsCommand(
                enabled = false,
                authenticatedRequestsPerMinute = 12,
                anonymousRequestsPerMinute = 6,
                excludedPaths = updated.excludedPaths,
                expectedVersion = 0,
            ),
        )
        assertEquals(RateLimitSettingsUpdateResult.Conflict(1), conflict)
    }

    companion object {
        @Container
        @JvmStatic
        private val redisContainer = GenericContainer("redis:7.4-alpine").withExposedPorts(REDIS_PORT)

        private const val REDIS_PORT = 6379
    }
}
