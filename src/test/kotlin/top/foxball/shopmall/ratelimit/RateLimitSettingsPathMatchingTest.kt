package top.foxball.shopmall.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.web.MockHttpServletRequest
import top.foxball.shopmall.service.AdminAccessService
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitSettingsPathMatchingTest {
    private val settingsService = RateLimitSettingsService(
        redis = mock(StringRedisTemplate::class.java),
        properties = RateLimitProperties(identityHashSecret = "a".repeat(32)),
        adminAccessService = mock(AdminAccessService::class.java),
        metrics = RateLimitMetrics(SimpleMeterRegistry()),
    )
    private val settings = RateLimitSettings(
        enabled = true,
        windowSeconds = 60,
        authenticatedRequestsPerMinute = 10,
        anonymousRequestsPerMinute = 5,
        excludedPaths = listOf("/api/catalog/**", "/api/files/public"),
        version = 3,
        source = RateLimitSettingsSource.REDIS,
        updatedAt = LocalDateTime.parse("2026-08-07T11:30:00"),
        updatedBy = 42,
        settingsId = "[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]",
        excludedPathsRaw = "/api/catalog/**\n/api/files/public",
    )

    @Test
    fun `matches exact and subtree paths without considering query parameters`() {
        assertTrue(settingsService.matchesExcludedPath(settings, request("/api/files/public", "download=true")))
        assertTrue(settingsService.matchesExcludedPath(settings, request("/api/catalog")))
        assertTrue(settingsService.matchesExcludedPath(settings, request("/api/catalog/items/42")))
        assertFalse(settingsService.matchesExcludedPath(settings, request("/api/files/public/42")))
    }

    @Test
    fun `noncanonical request path never matches dynamic exclusion`() {
        assertFalse(settingsService.matchesExcludedPath(settings, request("/api/catalog//items")))
        assertFalse(settingsService.matchesExcludedPath(settings, request("/api/catalog/%2fitems")))
        assertFalse(settingsService.matchesExcludedPath(settings, request("/api/catalog;v=1/items")))
    }

    private fun request(path: String, query: String? = null) = MockHttpServletRequest("GET", path).apply {
        queryString = query
    }
}
