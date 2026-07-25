package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
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
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.OrderLineCommand
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.OrderView
import top.foxball.shopmall.service.PlaceOrderCommand
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.util.UUID

class OrderControllerTest {
    private lateinit var orderService: OrderService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        orderService = mock(OrderService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(OrderController(orderService, ResponseBuilder()))
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "items": [{"productId": 40, "quantity": 1, "unitPrice": 0.01}],
                      "addressId": "$addressId",
                      "clientMessage": "front door"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.orderNo").value("ORD-API-1"))
            .andExpect(jsonPath("$.data.items[0].unitPrice").value(29.99))
            .andExpect(jsonPath("$.data.clientSecret").value("pi_secret"))

        verify(orderService).placeOrder(7L, expectedCommand, "idem-1")
    }

    @Test
    fun `invalid order body is rejected before service invocation`() {
        authenticate(7L)

        mockMvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "idem-invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "items": [{"productId": 40, "quantity": 100}],
                      "addressId": "${UUID.randomUUID()}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("quantity")))
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
        val page = PageImpl(listOf(orderView()), PageRequest.of(1, 5), 6)
        val expectedQuery = AdminOrderQuery(1, 5, OrderStatus.PENDING_PAYMENT, 7, "ORD-API")
        `when`(orderService.listAdmin(99L, expectedQuery)).thenReturn(page)

        mockMvc.perform(
            get("/api/admin/orders")
                .param("page", "1")
                .param("size", "5")
                .param("status", "PENDING_PAYMENT")
                .param("customerId", "7")
                .param("orderNo", "ORD-API"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(5))
            .andExpect(jsonPath("$.data.totalElements").value(6))
            .andExpect(jsonPath("$.data.orders[0].orderNo").value("ORD-API-1"))

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
            clientSecret = "pi_secret",
        )
    }
}
