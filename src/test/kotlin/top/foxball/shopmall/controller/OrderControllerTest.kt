package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderPaymentStatus
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.controller.admin.AdminOrderController
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.service.AdminOrderPaymentQuerySource
import top.foxball.shopmall.service.AdminOrderPaymentView
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.AdminOrderDetails
import top.foxball.shopmall.service.OrderLineCommand
import top.foxball.shopmall.service.OrderCheckoutService
import top.foxball.shopmall.service.OrderCheckoutView
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.OrderView
import top.foxball.shopmall.service.PlaceOrderCommand
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.IssuedKey
import top.foxball.shopmall.shared.OrderIdempotencyKeyService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentProviderId
import top.foxball.shopmall.service.payMent.PaymentStatus
import java.util.UUID

class OrderControllerTest {
    private lateinit var orderService: OrderService
    private lateinit var orderCheckoutService: OrderCheckoutService
    private lateinit var orderPaymentService: OrderPaymentService
    private lateinit var orderIdempotencyKeyService: OrderIdempotencyKeyService
    private lateinit var userService: UserService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        orderService = mock(OrderService::class.java)
        orderCheckoutService = mock(OrderCheckoutService::class.java)
        orderPaymentService = mock(OrderPaymentService::class.java)
        orderIdempotencyKeyService = mock(OrderIdempotencyKeyService::class.java)
        userService = mock(UserService::class.java)
        val customer = User(id = 7, username = "customer")
        `when`(userService.getUsernameById(7)).thenReturn("customer")
        `when`(userService.getUserByUsername("customer")).thenReturn(customer)
        `when`(userService.getUsernamesByIds(anyList())).thenReturn(mapOf(7L to "customer"))
        mockMvc = MockMvcBuilders.standaloneSetup(
            OrderController(orderService, orderCheckoutService, orderIdempotencyKeyService, ResponseBuilder()),
            AdminOrderController(orderService, orderPaymentService, userService, ResponseBuilder()),
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
                .param("variant_ids", "40")
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
                .param("variant_ids", "40")
                .param("quantities", "1")
                .param("address_id", UUID.randomUUID().toString()),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `open checkout without an idempotency key returns the server generated redirect URL`() {
        authenticate(7L)
        `when`(orderCheckoutService.openCheckout(7L, "ORD-API-1")).thenReturn(
            OrderCheckoutView(
                orderNo = "ORD-API-1",
                status = OrderStatus.PENDING_PAYMENT,
                checkoutUrl = "https://checkout.stripe.com/c/pay/cs_test_123",
                expiresAt = java.time.Instant.parse("2026-07-27T10:30:00Z"),
            ),
        )

        mockMvc.perform(post("/api/orders/ORD-API-1/checkout"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.data.checkout_url").value("https://checkout.stripe.com/c/pay/cs_test_123"))

        verify(orderCheckoutService).openCheckout(7L, "ORD-API-1")
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
                .param("variant_ids", "40")
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
    fun `customer can set an order shipping address as default`() {
        authenticate(7L)
        val addressId = UUID.randomUUID()
        `when`(orderService.saveShippingAddressAsDefault(7L, "ORD-API-1")).thenReturn(
            DeliveryAddressItem(
                id = addressId,
                name = "API User",
                phone = "+14155550123",
                country = "US",
                city = "Austin",
                address1 = "1 Main St",
                isDefault = true,
            ),
        )

        mockMvc.perform(post("/api/orders/ORD-API-1/shipping-address/default"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(addressId.toString()))
            .andExpect(jsonPath("$.data.address_line1").value("1 Main St"))
            .andExpect(jsonPath("$.data.is_default").value(true))

        verify(orderService).saveShippingAddressAsDefault(7L, "ORD-API-1")
    }

    @Test
    fun `customer can cancel an order without a reason`() {
        authenticate(7L)
        `when`(orderService.cancel(7L, "ORD-API-1", null)).thenReturn(orderView())

        mockMvc.perform(post("/api/orders/ORD-API-1/cancel"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))

        verify(orderService).cancel(7L, "ORD-API-1", null)
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
                .param("customer_username", "customer")
                .param("order_no", "ORD-API"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pagination.count").value(2))
            .andExpect(jsonPath("$.data.list[0].order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.list[0].customer_username").value("customer"))

        verify(orderService).listAdmin(99L, expectedQuery)
    }

    @Test
    fun `admin detail exposes address and fulfillment allocation`() {
        authenticate(99L)
        val view = orderView()
        view.order.stripeCheckoutSessionId = "cs_test_123"
        `when`(orderService.getAdmin(99L, "ORD-API-1")).thenReturn(
            AdminOrderDetails(view.order, view.items, mapOf(30L to 1)),
        )

        mockMvc.perform(get("/admin/api/orders/ORD-API-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.customer_username").value("customer"))
            .andExpect(jsonPath("$.data.shipping_address.address1").value("1 Main St"))
            .andExpect(jsonPath("$.data.stripe_checkout_session_id").value("cs_test_123"))
            .andExpect(jsonPath("$.data.items[0].allocated").value(true))
            .andExpect(jsonPath("$.data.items[0].allocated_quantity").value(1))
            .andExpect(jsonPath("$.data.items[0].remaining_quantity").value(0))

        verify(orderService).getAdmin(99L, "ORD-API-1")
    }

    @Test
    fun `admin can manually query Stripe payment status`() {
        authenticate(99L)
        `when`(orderPaymentService.queryAdminPaymentStatus(99L, "ORD-API-1")).thenReturn(
            AdminOrderPaymentView(
                orderNo = "ORD-API-1",
                orderStatus = OrderStatus.PENDING_PAYMENT,
                provider = PaymentProviderId("stripe"),
                providerStatus = PaymentStatus.SUCCEEDED,
                querySource = AdminOrderPaymentQuerySource.PAYMENT_INTENT,
                paymentIntentId = "pi_test_123",
                checkoutSessionId = "cs_test_123",
                paymentIntentStatus = "succeeded",
                checkoutSessionStatus = null,
                checkoutPaymentStatus = null,
                amount = PaymentAmount(BigDecimal("29.99"), "USD"),
                amountMatchesOrder = true,
                failureCode = null,
                failureMessage = null,
            ),
        )

        mockMvc.perform(post("/admin/api/orders/ORD-API-1/payment-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.provider").value("stripe"))
            .andExpect(jsonPath("$.data.provider_status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.data.query_source").value("PAYMENT_INTENT"))
            .andExpect(jsonPath("$.data.amount").value(29.99))
            .andExpect(jsonPath("$.data.amount_matches_order").value(true))

        verify(orderPaymentService).queryAdminPaymentStatus(99L, "ORD-API-1")
    }

    @Test
    fun `admin can manually set the final order status after querying Stripe`() {
        authenticate(99L)
        val updated = OrderEntity(
            id = 10,
            orderNo = "ORD-API-1",
            customerId = 7,
            status = OrderStatus.PAID,
            paymentStatus = OrderPaymentStatus.PAID,
        )
        `when`(orderService.updateAdminStatus(99L, "ORD-API-1", OrderStatus.PAID)).thenReturn(updated)

        mockMvc.perform(
            post("/admin/api/orders/ORD-API-1/status")
                .param("status", "PAID"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(10))
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.status").value("PAID"))
            .andExpect(jsonPath("$.data.payment_status").value("PAID"))

        verify(orderService).updateAdminStatus(99L, "ORD-API-1", OrderStatus.PAID)
    }

    @Test
    fun `admin refund relies on the transactional outbox`() {
        authenticate(99L)
        val order = OrderEntity(
            id = 10,
            orderNo = "ORD-API-1",
            customerId = 7,
            status = OrderStatus.REFUNDING,
            paymentStatus = OrderPaymentStatus.REFUNDING,
        )
        `when`(orderService.refund(99L, "ORD-API-1", "duplicate charge"))
            .thenReturn(OrderView(order, emptyList()))

        mockMvc.perform(
            post("/admin/api/orders/ORD-API-1/refund")
                .param("reason", "duplicate charge"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.status").value("REFUNDING"))
            .andExpect(jsonPath("$.data.payment_status").value("REFUNDING"))

        verify(orderService).refund(99L, "ORD-API-1", "duplicate charge")
        verify(orderPaymentService, never()).reconcileRequestedRefund(10)
    }

    @Test
    fun `admin deletion reports logical deletion`() {
        authenticate(99L)
        `when`(orderService.delete(99L, "ORD-API-1")).thenReturn(
            OrderEntity(orderNo = "ORD-API-1", status = OrderStatus.DELETED),
        )

        mockMvc.perform(delete("/admin/api/orders/ORD-API-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.status").value("DELETED"))

        verify(orderService).delete(99L, "ORD-API-1")
    }

    @Test
    fun `admin permanent deletion uses explicit endpoint`() {
        authenticate(99L)

        mockMvc.perform(delete("/admin/api/orders/ORD-API-1/permanent"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.order_no").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.physically_deleted").value(true))

        verify(orderService).permanentlyDelete(99L, "ORD-API-1")
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
                    variantId = 400,
                    sku = "TEST-SKU-400",
                    productSnapshot = "{\"name\":\"Snapshot\"}",
                    unitPrice = BigDecimal("29.99"),
                    quantity = 1,
                    lineTotal = BigDecimal("29.99"),
                ),
            ),
        )
    }
}
