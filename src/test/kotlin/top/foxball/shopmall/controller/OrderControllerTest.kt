package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.controller.admin.AdminOrderController
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.OrderLineCommand
import top.foxball.shopmall.service.OrderCheckoutService
import top.foxball.shopmall.service.OrderCheckoutView
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.OrderView
import top.foxball.shopmall.service.PlaceOrderCommand
import top.foxball.shopmall.shared.IssuedKey
import top.foxball.shopmall.shared.OrderIdempotencyKeyService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.util.UUID

class OrderControllerTest {
    private lateinit var orderService: OrderService
    private lateinit var orderCheckoutService: OrderCheckoutService
    private lateinit var orderIdempotencyKeyService: OrderIdempotencyKeyService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        orderService = mock(OrderService::class.java)
        orderCheckoutService = mock(OrderCheckoutService::class.java)
        orderIdempotencyKeyService = mock(OrderIdempotencyKeyService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            OrderController(orderService, orderCheckoutService, orderIdempotencyKeyService, ResponseBuilder()),
            AdminOrderController(orderService, ResponseBuilder()),
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `place order ignores client supplied prices and returns server snapshot`() {
        authenticate(7L)
        val addressId = UUID.randomUUID()
        val expectedCommand = PlaceOrderCommand(
            items = listOf(OrderLineCommand(40, 1)),
            addressId = addressId,
            clientMessage = "front door",
        )
        `when`(orderService.placeOrder(7L, expectedCommand, "idem-1")).thenReturn(orderView())

        mockMvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "idem-1")
                .param("product_ids", "40")
                .param("quantities", "1")
                .param("address_id", addressId.toString())
                .param("client_message", "front door"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.items[0].unit_price").value(29.99))

        verify(orderService).placeOrder(7L, expectedCommand, "idem-1")
    }

    @Test
    fun `place order without a server issued idempotency key is rejected`() {
        authenticate(7L)

        mockMvc.perform(
            post("/api/orders")
                .param("product_ids", "40")
                .param("quantities", "1")
                .param("address_id", UUID.randomUUID().toString()),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `open checkout returns only server generated redirect URL`() {
        authenticate(7L)
        `when`(orderCheckoutService.openCheckout(7L, "ORD-API-1", "idem-1")).thenReturn(
            OrderCheckoutView(
                orderNo = "ORD-API-1",
                status = OrderStatus.PENDING_PAYMENT,
                checkoutUrl = "https://checkout.stripe.com/c/pay/cs_test_123",
                expiresAt = java.time.Instant.parse("2026-07-27T10:30:00Z"),
            ),
        )

        mockMvc.perform(
            post("/api/orders/ORD-API-1/checkout")
                .header("Idempotency-Key", "idem-1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.data.checkout_url").value("https://checkout.stripe.com/c/pay/cs_test_123"))

        verify(orderCheckoutService).openCheckout(7L, "ORD-API-1", "idem-1")
    }

    @Test
    fun `checkout without an idempotency key is rejected`() {
        authenticate(7L)

        mockMvc.perform(post("/api/orders/ORD-API-1/checkout"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `issuing an idempotency key returns the issued key and expiry`() {
        authenticate(7L)
        val expiresAt = java.time.Instant.parse("2026-07-27T10:40:00Z")
        `when`(orderIdempotencyKeyService.issue(7L))
            .thenReturn(IssuedKey("issued-key-1", expiresAt))

        mockMvc.perform(post("/api/orders/idempotency-keys"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.idempotency_key").value("issued-key-1"))
            .andExpect(jsonPath("$.data.expires_at").value("2026-07-27T10:40:00Z"))

        verify(orderIdempotencyKeyService).issue(7L)
    }

    @Test
    fun `invalid order parameters are rejected before service invocation`() {
        authenticate(7L)

        mockMvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "idem-invalid")
                .param("product_ids", "40")
                .param("quantities", "100")
                .param("address_id", UUID.randomUUID().toString()),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `customer detail keeps non-owned orders indistinguishable from missing orders`() {
        authenticate(7L)
        `when`(orderService.getCustomer(7L, "ORD-HIDDEN")).thenThrow(OrderNotFoundException())

        mockMvc.perform(get("/api/orders/ORD-HIDDEN"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").value("订单不存在"))
    }

    @Test
    fun `admin list forwards pagination and filters`() {
        authenticate(99L)
        val page = PageImpl(listOf(orderView()), PageRequest.of(0, 5), 6)
        val expectedQuery = AdminOrderQuery(0, 5, OrderStatus.PENDING_PAYMENT, 7, "ORD-API")
        `when`(orderService.listAdmin(99L, expectedQuery)).thenReturn(page)

        mockMvc.perform(
            get("/admin/api/orders")
                .param("page", "1")
                .param("size", "5")
                .param("status", "PENDING_PAYMENT")
                .param("customer_id", "7")
                .param("order_no", "ORD-API"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pagination.count").value(2))
            .andExpect(jsonPath("$.data.list[0].order_no").value("ORD-API-1"))

        verify(orderService).listAdmin(99L, expectedQuery)
    }

    private fun authenticate(userId: Long) {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = TestingAuthenticationToken(userId, null)
        SecurityContextHolder.setContext(context)
    }

    private fun orderView(): OrderView {
        val order = OrderEntity(
            id = 10,
            orderNo = "ORD-API-1",
            customerId = 7,
            status = OrderStatus.PENDING_PAYMENT,
            itemsSubtotal = BigDecimal("29.99"),
            totalAmount = BigDecimal("29.99"),
            paymentIntentId = "pi_123",
            shippingAddress = OrderShippingAddress(
                name = "API User",
                phone = "+14155550123",
                country = "US",
                city = "Austin",
                address1 = "1 Main St",
            ),
        )
        return OrderView(
            order = order,
            items = listOf(
                OrderItem(
                    id = 30,
                    order = order,
                    productId = 40,
                    productSnapshot = "{\"name\":\"Snapshot\"}",
                    unitPrice = BigDecimal("29.99"),
                    quantity = 1,
                    lineTotal = BigDecimal("29.99"),
                ),
            ),
        )
    }
}
