package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.service.ShoppingCartItemView
import top.foxball.shopmall.service.ShoppingCartService
import top.foxball.shopmall.service.ShoppingCartView
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal

class ShoppingCartControllerTest {
    private lateinit var shoppingCartService: ShoppingCartService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        shoppingCartService = mock(ShoppingCartService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            ShoppingCartController(shoppingCartService, ResponseBuilder()),
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
    fun `get cart returns product and server calculated totals`() {
        authenticate(7)
        `when`(shoppingCartService.getCart(7)).thenReturn(cartView())

        mockMvc.perform(get("/api/cart"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.customer_id").value(7))
            .andExpect(jsonPath("$.data.items[0].product_id").value(40))
            .andExpect(jsonPath("$.data.items[0].product_type").value("BIKINI"))
            .andExpect(jsonPath("$.data.items[0].line_total").value(59.98))
            .andExpect(jsonPath("$.data.total_quantity").value(2))
            .andExpect(jsonPath("$.data.subtotal").value(59.98))

        verify(shoppingCartService).getCart(7)
    }

    @Test
    fun `add item forwards authenticated user and request parameters`() {
        authenticate(7)
        `when`(shoppingCartService.addItem(7, 40, 2)).thenReturn(cartView())

        mockMvc.perform(
            post("/api/cart/items")
                .param("product_id", "40")
                .param("quantity", "2"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.items[0].quantity").value(2))

        verify(shoppingCartService).addItem(7, 40, 2)
    }

    @Test
    fun `removing a non-owned item returns not found`() {
        authenticate(7)
        `when`(shoppingCartService.removeItem(7, 999)).thenReturn(null)

        mockMvc.perform(delete("/api/cart/items/999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").value("购物车商品不存在"))
    }

    @Test
    fun `invalid item quantity returns a unified bad request response`() {
        authenticate(7)

        mockMvc.perform(
            post("/api/cart/items")
                .param("product_id", "40")
                .param("quantity", "100"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))

        verifyNoInteractions(shoppingCartService)
    }

    private fun authenticate(userId: Long) {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = TestingAuthenticationToken(userId, null)
        SecurityContextHolder.setContext(context)
    }

    private fun cartView() = ShoppingCartView(
        customerId = 7,
        items = listOf(
            ShoppingCartItemView(
                id = 10,
                productId = 40,
                productType = "BIKINI",
                name = "Ocean Bikini",
                color = "Blue",
                size = null,
                topSize = "M",
                bottomSize = "M",
                unitPrice = BigDecimal("29.99"),
                quantity = 2,
                lineTotal = BigDecimal("59.98"),
                stock = 8,
                productStatus = "ACTIVE",
                purchasable = true,
                primaryImage = "https://example.com/product.jpg",
                createdAt = null,
                updatedAt = null,
            ),
        ),
        totalQuantity = 2,
        subtotal = BigDecimal("59.98"),
        updatedAt = null,
    )
}
