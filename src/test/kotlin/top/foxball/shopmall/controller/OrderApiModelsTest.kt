package top.foxball.shopmall.controller

import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderApiModelsTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `place order request validates line count and quantity limits`() {
        val empty = PlaceOrderRequest(emptyList(), UUID.randomUUID())
        val excessiveQuantity = PlaceOrderRequest(
            items = listOf(OrderLineRequest(productId = 1, quantity = 100)),
            addressId = UUID.randomUUID(),
        )

        assertTrue(validator.validate(empty).any { it.propertyPath.toString() == "items" })
        assertTrue(
            validator.validate(excessiveQuantity)
                .any { it.propertyPath.toString() == "items[0].quantity" },
        )
    }

    @Test
    fun `cancel and refund reasons must be meaningful`() {
        assertTrue(validator.validate(CancelOrderRequest(" ")).isNotEmpty())
        assertTrue(validator.validate(RefundOrderRequest("x".repeat(201))).isNotEmpty())
    }

    @Test
    fun `order response maps the persisted snapshots and payment data`() {
        val order = OrderEntity(
            id = 10,
            orderNo = "ORD-API-1",
            customerId = 20,
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
        val item = OrderItem(
            id = 30,
            order = order,
            productId = 40,
            productSnapshot = "{\"name\":\"Snapshot\"}",
            unitPrice = BigDecimal("29.99"),
            quantity = 1,
            lineTotal = BigDecimal("29.99"),
        )

        val response = order.toResponse(listOf(item), clientSecret = "pi_secret")

        assertEquals("ORD-API-1", response.orderNo)
        assertEquals("pi_123", response.paymentIntentId)
        assertEquals("pi_secret", response.clientSecret)
        assertEquals("API User", response.shippingAddress.name)
        assertEquals(40, response.items.single().productId)
        assertEquals("{\"name\":\"Snapshot\"}", response.items.single().productSnapshot)
    }
}
