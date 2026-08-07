package top.foxball.shopmall.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiRateLimitServiceTest {
    private val redis = mock(StringRedisTemplate::class.java)
    private val service = ApiRateLimitService(
        redis = redis,
        properties = RateLimitProperties(identityHashSecret = "a".repeat(32)),
        metrics = RateLimitMetrics(SimpleMeterRegistry()),
    )

    @Test
    fun `maps allowed Redis decision`() {
        stubDecision("1:10:9:0")

        val decision = service.decide(RateLimitIdentityType.AUTHENTICATED, "42", 10)

        assertTrue(decision.allowed)
        assertEquals(10, decision.limit)
        assertEquals(9, decision.remaining)
        assertEquals(0, decision.retryAfterSeconds)
    }

    @Test
    fun `maps rejected Redis decision`() {
        stubDecision("0:5:0:37")

        val decision = service.decide(RateLimitIdentityType.ANONYMOUS, "198.51.100.9", 5)

        assertFalse(decision.allowed)
        assertEquals(5, decision.limit)
        assertEquals(0, decision.remaining)
        assertEquals(37, decision.retryAfterSeconds)
    }

    @Test
    fun `fails closed for malformed Redis result and invalid subject`() {
        stubDecision("unexpected")
        assertFailsWith<RateLimitUnavailableException> {
            service.decide(RateLimitIdentityType.AUTHENTICATED, "42", 10)
        }
        assertFailsWith<RateLimitUnavailableException> {
            service.decide(RateLimitIdentityType.AUTHENTICATED, "not-a-user", 10)
        }
    }

    private fun stubDecision(value: String?) {
        `when`(
            redis.execute<String>(
                any(),
                any<List<String>>(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(value)
    }
}
