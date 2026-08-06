package top.foxball.shopmall.service

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.UserRepository
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
class OrderDeletionIntegrationTest {
    @Autowired
    private lateinit var orderService: OrderService

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var orderItemRepository: OrderItemRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    @Transactional
    fun `order is hidden after logical deletion and removed with its items by permanent deletion`() {
        val admin = userRepository.saveAndFlush(
            User(
                email = "order-delete-admin@example.com",
                username = "order-delete-admin",
                password = "encoded-password",
                role = Role.ADMIN,
            ),
        )
        val order = orderRepository.saveAndFlush(
            OrderEntity(
                orderNo = "ORDER-DELETE-INTEGRATION",
                customerId = 7001,
                status = OrderStatus.COMPLETED,
                itemsSubtotal = BigDecimal("39.90"),
                totalAmount = BigDecimal("39.90"),
                shippingAddress = OrderShippingAddress(
                    name = "Delete Test",
                    phone = "+14155550123",
                    country = "US",
                    city = "Austin",
                    address1 = "1 Main St",
                ),
            ),
        )
        val item = orderItemRepository.saveAndFlush(
            OrderItem(
                order = order,
                productId = 8001,
                productSnapshot = "{}",
                unitPrice = BigDecimal("39.90"),
                quantity = 1,
                lineTotal = BigDecimal("39.90"),
            ),
        )
        val orderId = requireNotNull(order.id)
        val itemId = requireNotNull(item.id)

        orderService.delete(requireNotNull(admin.id), order.orderNo)

        assertEquals(OrderStatus.DELETED, orderRepository.findById(orderId).orElseThrow().status)
        assertNull(orderRepository.findByOrderNoAndCustomerId(order.orderNo, order.customerId))
        assertTrue(
            orderRepository.findByCustomerIdOrderByCreatedAtDesc(
                order.customerId,
                PageRequest.of(0, 20),
            ).isEmpty,
        )

        orderService.permanentlyDelete(requireNotNull(admin.id), order.orderNo)

        assertFalse(orderRepository.existsById(orderId))
        assertFalse(orderItemRepository.existsById(itemId))
    }
}
