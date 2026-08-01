package top.foxball.shopmall.shared

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.OrderWindowLimitException
import top.foxball.shopmall.repository.OrderRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderIdempotencyKeyServiceTest {
    private val redis = mock(StringRedisTemplate::class.java)
    private val orderRepository = mock(OrderRepository::class.java)
    @Suppress("UNCHECKED_CAST")
    private val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val clock = Clock.fixed(Instant.parse("2026-07-25T03:00:00Z"), ZoneOffset.UTC)
    private val properties = OrderProperties()
    private val service = OrderIdempotencyKeyService(redis, properties, clock, orderRepository)

    init {
        `when`(redis.opsForValue()).thenReturn(values)
    }

    @Test
    fun `issue creates a key with expiry when no key exists`() {
        `when`(values.get("order:key:5")).thenReturn(null)
        `when`(
            redis.execute<String>(
                any(),
                any<List<String>>(),
                any(),
                any(),
            ),
        ).thenReturn("issued-key")
        `when`(redis.getExpire("order:key:5")).thenReturn(600L)
        `when`(orderRepository.findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList(), PageRequest.of(0, 1), 0))

        val issued = service.issue(5)

        assertEquals("issued-key", issued.value)
        assertEquals(clock.instant().plus(Duration.ofMinutes(10)), issued.expiresAt)
    }

    @Test
    fun `issue returns the existing key when already issued`() {
        `when`(values.get("order:key:5")).thenReturn("existing-key")
        `when`(redis.getExpire("order:key:5")).thenReturn(120L)

        val issued = service.issue(5)

        assertEquals("existing-key", issued.value)
        assertEquals(clock.instant().plus(Duration.ofMinutes(2)), issued.expiresAt)
    }

    @Test
    fun `issue rejects when the latest order falls inside the creation window`() {
        `when`(values.get("order:key:5")).thenReturn(null)
        `when`(orderRepository.findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1)))
            .thenReturn(PageImpl(listOf(recentOrder(clock.instant().minusSeconds(60))), PageRequest.of(0, 1), 1))

        val ex = assertFailsWith<OrderWindowLimitException> { service.issue(5) }

        assertEquals(540L, ex.retryAfterSeconds)
    }

    @Test
    fun `issue passes when the latest order predates the creation window`() {
        `when`(values.get("order:key:5")).thenReturn(null)
        `when`(orderRepository.findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1)))
            .thenReturn(PageImpl(listOf(recentOrder(clock.instant().minusSeconds(600))), PageRequest.of(0, 1), 1))
        `when`(
            redis.execute<String>(
                any(),
                any<List<String>>(),
                any(),
                any(),
            ),
        ).thenReturn("issued-key")
        `when`(redis.getExpire("order:key:5")).thenReturn(600L)

        val issued = service.issue(5)

        assertEquals("issued-key", issued.value)
    }

    @Test
    fun `validation accepts only the current key issued to the customer`() {
        `when`(values.get("order:key:5")).thenReturn("issued-key")

        assertTrue(service.isValidFor(5, "issued-key"))
        assertFalse(service.isValidFor(5, "another-key"))
        assertFalse(service.isValidFor(5, "   "))
    }

    @Test
    fun `validation rejects a key issued to another customer without issuing a new key`() {
        `when`(values.get("order:key:6")).thenReturn(null)

        assertFalse(service.isValidFor(6, "issued-key"))
    }

    @Test
    fun `consume deletes only when the value matches the given key`() {
        `when`(
            redis.execute<Long>(
                any(),
                any<List<String>>(),
                any(),
            ),
        ).thenReturn(1L)

        assertTrue(service.consume(5, "issued-key"))
    }

    @Test
    fun `consume is a no-op when the stored key differs`() {
        `when`(
            redis.execute<Long>(
                any(),
                any<List<String>>(),
                any(),
            ),
        ).thenReturn(0L)

        assertFalse(service.consume(5, "stale-key"))
    }

    private fun recentOrder(createdAt: Instant): OrderEntity = OrderEntity(
        id = 1,
        orderNo = "ORDER-1",
        customerId = 5,
        status = OrderStatus.PENDING_PAYMENT,
        createdAt = createdAt,
    )
}
