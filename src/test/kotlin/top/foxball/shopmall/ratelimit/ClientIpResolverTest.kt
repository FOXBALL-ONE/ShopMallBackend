package top.foxball.shopmall.ratelimit

import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIpResolverTest {
    @Test
    fun `direct caller cannot forge x forwarded for`() {
        val request = request("198.51.100.9").apply {
            addHeader("X-Forwarded-For", "203.0.113.8")
        }

        assertEquals("198.51.100.9", resolver().resolve(request))
    }

    @Test
    fun `trusted proxy selects the first non proxy hop from forwarded chain`() {
        val request = request("10.0.0.2").apply {
            addHeader("Forwarded", "for=198.51.100.4, for=10.0.0.1")
        }

        assertEquals("198.51.100.4", resolver().resolve(request))
    }

    @Test
    fun `trusted proxy falls back to remote address when forwarded header is invalid`() {
        val request = request("10.0.0.2").apply {
            addHeader("Forwarded", "for=_hidden")
            addHeader("X-Forwarded-For", "198.51.100.4")
        }

        assertEquals("10.0.0.2", resolver().resolve(request))
    }

    @Test
    fun `trusted proxy uses x forwarded for only when forwarded is absent`() {
        val request = request("10.0.0.2").apply {
            addHeader("X-Forwarded-For", "198.51.100.4, 10.0.0.1")
        }

        assertEquals("198.51.100.4", resolver().resolve(request))
    }

    private fun resolver(): ClientIpResolver = ClientIpResolver(
        RateLimitProperties(
            identityHashSecret = "a".repeat(32),
            trustedProxyCidrs = listOf("10.0.0.0/8"),
        ),
    )

    private fun request(remoteAddress: String) = MockHttpServletRequest("GET", "/api/catalog").apply {
        remoteAddr = remoteAddress
    }
}
