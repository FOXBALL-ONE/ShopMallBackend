package top.foxball.shopmall.repository

import com.stripe.model.Event
import com.stripe.model.EventDataObjectDeserializer
import com.stripe.model.StripeObject
import com.stripe.model.checkout.Session
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.service.OrderPaymentService
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderPersistencePostgresIntegrationTest @Autowired constructor(
    private val productRepository: ProductRepository,
    private val webhookEventRepository: StripeWebhookEventRepository,
    private val orderRepository: OrderRepository,
    private val orderPaymentService: OrderPaymentService,
) {
    @Test
    fun `stock and sales updates target the joined product root atomically`() {
        val productId = requireNotNull(
            productRepository.saveAndFlush(
                Dress(size = Dress.Size.M).apply {
                    name = "Postgres stock test"
                    color = "Blue"
                    price = BigDecimal("29.99")
                    warehouseVolume = 5
                },
            ).id,
        )

        assertEquals(1, productRepository.decrementStock(productId, 2))
        assertEquals(0, productRepository.decrementStock(productId, 4))
        assertEquals(3, productRepository.findById(productId).orElseThrow().warehouseVolume)

        assertEquals(1, productRepository.incrementSales(productId, 2))
        assertEquals(2, productRepository.findById(productId).orElseThrow().salesVolume)
        assertEquals(1, productRepository.restock(productId, 2))
        assertEquals(5, productRepository.findById(productId).orElseThrow().warehouseVolume)
        assertEquals(1, productRepository.decrementSales(productId, 2))
        assertEquals(0, productRepository.findById(productId).orElseThrow().salesVolume)
    }

    @Test
    fun `stripe webhook event claim is database idempotent`() {
        assertEquals(1, webhookEventRepository.claim("evt_pg_1", "payment_intent.succeeded"))
        assertEquals(0, webhookEventRepository.claim("evt_pg_1", "payment_intent.succeeded"))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `unreadable Checkout event rolls back claim and same event can be retried`() {
        val eventId = "evt_pg_checkout_retry"
        val eventType = "checkout.session.completed"
        val unreadable = checkoutEvent(eventId, eventType, null)

        assertFailsWith<IllegalStateException> { orderPaymentService.handleWebhookEvent(unreadable) }
        assertFalse(webhookEventRepository.existsById(eventId))

        val order = orderRepository.saveAndFlush(
            OrderEntity(
                orderNo = "ORD-PG-WEBHOOK-RETRY",
                customerId = 7,
                status = OrderStatus.PENDING_PAYMENT,
                itemsSubtotal = BigDecimal("29.99"),
                totalAmount = BigDecimal("29.99"),
                stripeCheckoutSessionId = "cs_pg_retry",
                expiresAt = Instant.parse("2026-07-28T10:00:00Z"),
                shippingAddress = OrderShippingAddress(
                    name = "Webhook Test",
                    phone = "+14155550123",
                    country = "US",
                    city = "Austin",
                    address1 = "1 Main St",
                ),
            ),
        )
        val session = mock(Session::class.java).also {
            `when`(it.id).thenReturn("cs_pg_retry")
            `when`(it.paymentIntent).thenReturn("pi_pg_retry")
            `when`(it.paymentStatus).thenReturn("paid")
        }

        orderPaymentService.handleWebhookEvent(checkoutEvent(eventId, eventType, session))

        assertTrue(webhookEventRepository.existsById(eventId))
        assertEquals(OrderStatus.PAID, orderRepository.findById(requireNotNull(order.id)).orElseThrow().status)
    }

    private fun checkoutEvent(id: String, type: String, eventObject: StripeObject?): Event {
        val deserializer = mock(EventDataObjectDeserializer::class.java)
        `when`(deserializer.getObject()).thenReturn(Optional.ofNullable(eventObject))
        return mock(Event::class.java).also { event ->
            `when`(event.id).thenReturn(id)
            `when`(event.type).thenReturn(type)
            `when`(event.apiVersion).thenReturn("2025-12-15.clover")
            `when`(event.dataObjectDeserializer).thenReturn(deserializer)
        }
    }

    companion object {
        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.flyway.enabled") { false }
        }
    }
}
