package top.foxball.shopmall.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiRateLimitFilterTest {
    private val settingsService = mock(RateLimitSettingsService::class.java)
    private val decisionService = mock(ApiRateLimitService::class.java)
    private val clientIpResolver = mock(ClientIpResolver::class.java)
    private val metrics = RateLimitMetrics(SimpleMeterRegistry())
    private val filter = ApiRateLimitFilter(
        settingsService = settingsService,
        rateLimitService = decisionService,
        clientIpResolver = clientIpResolver,
        objectMapper = JsonMapper(),
        metrics = metrics,
    )

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `allowed authenticated request continues with quota headers`() {
        val request = request("/api/orders")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        val settings = stubSettings()
        `when`(settingsService.matchesExcludedPath(settings, request)).thenReturn(false)
        `when`(
            decisionService.decide(RateLimitIdentityType.AUTHENTICATED, "42", 10),
        ).thenReturn(RateLimitDecision(allowed = true, limit = 10, remaining = 9, retryAfterSeconds = 0))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(42L, null, emptyList())

        filter.doFilter(request, response, chain)

        assertEquals("10", response.getHeader("X-RateLimit-Limit"))
        assertEquals("9", response.getHeader("X-RateLimit-Remaining"))
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `rejected anonymous request writes unified 429 response`() {
        val request = request("/api/products")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        val settings = stubSettings()
        `when`(settingsService.matchesExcludedPath(settings, request)).thenReturn(false)
        `when`(clientIpResolver.resolve(request)).thenReturn("198.51.100.9")
        `when`(
            decisionService.decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5),
        ).thenReturn(RateLimitDecision(allowed = false, limit = 5, remaining = 0, retryAfterSeconds = 37))

        filter.doFilter(request, response, chain)

        assertEquals(429, response.status)
        assertEquals("37", response.getHeader("Retry-After"))
        assertEquals("5", response.getHeader("X-RateLimit-Limit"))
        assertEquals("0", response.getHeader("X-RateLimit-Remaining"))
        assertTrue(response.contentAsString.contains("\"status\":429"))
        verifyNoInteractions(chain)
    }

    @Test
    fun `dynamic exclusion skips ip resolution and Redis bucket decision`() {
        val request = request("/api/catalog/public")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        val settings = stubSettings(excludedPaths = listOf("/api/catalog/**"))
        `when`(settingsService.matchesExcludedPath(settings, request)).thenReturn(true)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        verifyNoInteractions(decisionService, clientIpResolver)
        assertEquals(null, response.getHeader("X-RateLimit-Limit"))
    }

    @Test
    fun `settings failure closes request with unified 503 response`() {
        val request = request("/api/products")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        `when`(settingsService.getSettings()).thenThrow(RateLimitUnavailableException("Redis down"))

        filter.doFilter(request, response, chain)

        assertEquals(503, response.status)
        assertEquals("1", response.getHeader("Retry-After"))
        assertTrue(response.contentAsString.contains("\"status\":503"))
        verifyNoInteractions(chain)
    }

    @Test
    fun `disabled settings continue without matching or bucket decision`() {
        val request = request("/api/products")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        stubSettings().also { settings ->
            val disabled = settings.copy(enabled = false)
            `when`(settingsService.getSettings()).thenReturn(disabled)
        }

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        verifyNoInteractions(decisionService, clientIpResolver)
        assertEquals(null, response.getHeader("X-RateLimit-Limit"))
    }

    @Test
    fun `malformed managed path still enters rate limiting`() {
        val request = request("/api;v=1/products")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        val settings = stubSettings()
        `when`(settingsService.matchesExcludedPath(settings, request)).thenReturn(false)
        `when`(clientIpResolver.resolve(request)).thenReturn("198.51.100.9")
        `when`(
            decisionService.decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5),
        ).thenReturn(RateLimitDecision(allowed = true, limit = 5, remaining = 4, retryAfterSeconds = 0))

        filter.doFilter(request, response, chain)

        verify(settingsService).getSettings()
        verify(decisionService).decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `non post root webhook enters rate limiting`() {
        val request = request("/webhook")
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        val settings = stubSettings()
        `when`(settingsService.matchesExcludedPath(settings, request)).thenReturn(false)
        `when`(clientIpResolver.resolve(request)).thenReturn("198.51.100.9")
        `when`(
            decisionService.decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5),
        ).thenReturn(RateLimitDecision(allowed = true, limit = 5, remaining = 4, retryAfterSeconds = 0))

        filter.doFilter(request, response, chain)

        verify(settingsService).getSettings()
        verify(decisionService).decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `options and fixed webhook exclusions do not access settings`() {
        val optionsRequest = MockHttpServletRequest("OPTIONS", "/api/products")
        val rootWebhookRequest = MockHttpServletRequest("POST", "/webhook")
        val webhookRequest = MockHttpServletRequest("POST", "/api/logistics/webhook/carrier")
        val optionsResponse = MockHttpServletResponse()
        val rootWebhookResponse = MockHttpServletResponse()
        val webhookResponse = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(optionsRequest, optionsResponse, chain)
        filter.doFilter(rootWebhookRequest, rootWebhookResponse, chain)
        filter.doFilter(webhookRequest, webhookResponse, chain)

        verify(chain).doFilter(optionsRequest, optionsResponse)
        verify(chain).doFilter(rootWebhookRequest, rootWebhookResponse)
        verify(chain).doFilter(webhookRequest, webhookResponse)
        verifyNoInteractions(settingsService, decisionService, clientIpResolver)
    }

    private fun stubSettings(excludedPaths: List<String> = emptyList()): RateLimitSettings {
        val rawPaths = excludedPaths.joinToString("\n")
        val settings = RateLimitSettings(
            enabled = true,
            windowSeconds = 60,
            authenticatedRequestsPerMinute = 10,
            anonymousRequestsPerMinute = 5,
            excludedPaths = excludedPaths,
            version = 1,
            source = RateLimitSettingsSource.REDIS,
            updatedAt = LocalDateTime.parse("2026-08-07T11:30:00"),
            updatedBy = 42,
            settingsId = "[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]",
            excludedPathsRaw = rawPaths,
        )
        `when`(settingsService.getSettings()).thenReturn(settings)
        return settings
    }

    private fun request(path: String) = MockHttpServletRequest("GET", path).apply {
        remoteAddr = "198.51.100.9"
    }
}
