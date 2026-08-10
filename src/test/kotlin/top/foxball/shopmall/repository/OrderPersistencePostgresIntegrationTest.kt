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
import org.springframework.data.domain.PageRequest
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
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.service.AdminUserQuery
import top.foxball.shopmall.service.AdminUserService
import top.foxball.shopmall.service.OrderPaymentService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
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
    private val dashboardReportRepository: AdminDashboardReportRepository,
    private val shipmentRepository: ShipmentRepository,
    private val userRepository: UserRepository,
    private val adminUserService: AdminUserService,
) {
    @Test
    fun `admin user query binds an absent keyword as text on PostgreSQL`() {
        val admin = userRepository.saveAndFlush(
            User(
                email = "pg-query-admin@example.test",
                username = "pg-query-admin",
                password = "encoded-password",
                role = Role.ADMIN,
            ),
        )
        userRepository.saveAndFlush(
            User(
                email = "alice@example.test",
                username = "alice",
                password = "encoded-password",
                firstName = "Alice",
                lastName = "Smith",
            ),
        )

        val allUsers = adminUserService.list(requireNotNull(admin.id), AdminUserQuery())
        val matchedUsers = adminUserService.list(
            requireNotNull(admin.id),
            AdminUserQuery(keyword = "ALI"),
        )

        assertEquals(setOf("pg-query-admin", "alice"), allUsers.content.map(User::username).toSet())
        assertEquals(listOf("alice"), matchedUsers.content.map(User::username))
    }

    @Test
    fun `admin shipment query binds an absent tracking number as text on PostgreSQL`() {
        val shipments = shipmentRepository.findAllForAdmin(
            status = null,
            deleted = ShipmentStatus.DELETED,
            carrier = null,
            orderNo = null,
            trackingNo = "",
            hasError = null,
            pageable = PageRequest.of(0, 20),
        )

        assertTrue(shipments.isEmpty)
    }

    @Test
    fun `dashboard reports bind LocalDateTime ranges on PostgreSQL`() {
        val from = LocalDateTime.parse("1900-01-01T00:00:00")
        val until = LocalDateTime.parse("1900-01-02T00:00:00")

        assertTrue(dashboardReportRepository.findDailyOrderCounts(from, until).isEmpty())
        assertTrue(dashboardReportRepository.findDailyRevenue(from, until).isEmpty())
        assertTrue(dashboardReportRepository.findDailyCustomerCounts(from, until).isEmpty())
    }

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
