package top.foxball.shopmall.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.SupportTicketMessageRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.repository.UserRepository
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:mock-data-initializer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shopmall.mock-data.enabled=true",
        "shopmall.mock-data.password=integration-password",
    ],
)
@ActiveProfiles("test")
@Transactional
class MockDataInitializerIntegrationTest @Autowired constructor(
    private val initializer: MockDataInitializer,
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val shipmentItemRepository: ShipmentItemRepository,
    private val supportTicketRepository: SupportTicketRepository,
    private val supportTicketMessageRepository: SupportTicketMessageRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `startup initialization writes complete historical data once`() {
        assertEquals(38, userRepository.count())
        assertEquals(16, productRepository.count())
        assertEquals(180, orderRepository.count())
        assertEquals(180, orderItemRepository.count())
        assertEquals(120, shipmentRepository.count())
        assertEquals(120, shipmentItemRepository.count())
        assertEquals(24, supportTicketRepository.count())
        assertEquals(42, supportTicketMessageRepository.count())

        OrderStatus.entries.filterNot {
            it in setOf(OrderStatus.DELETED, OrderStatus.REFUNDING, OrderStatus.REFUNDED)
        }.forEach { status ->
            assertEquals(30, orderRepository.countByStatus(status), "订单状态 $status 数量不正确")
        }
        assertEquals(13, productRepository.countByStatus(Product.Status.ACTIVE))
        assertEquals(2, productRepository.countByStatus(Product.Status.INACTIVE))
        assertEquals(1, productRepository.countByStatus(Product.Status.DELETED))
        assertEquals(8, shipmentRepository.countByStatus(ShipmentStatus.LABEL_PENDING))
        assertEquals(8, shipmentRepository.countByStatus(ShipmentStatus.LABEL_CREATED))
        assertEquals(7, shipmentRepository.countByStatus(ShipmentStatus.CANCEL_PENDING))
        assertEquals(15, shipmentRepository.countByStatus(ShipmentStatus.IN_TRANSIT))
        assertEquals(15, shipmentRepository.countByStatus(ShipmentStatus.OUT_FOR_DELIVERY))
        assertEquals(60, shipmentRepository.countByStatus(ShipmentStatus.DELIVERED))
        assertEquals(7, shipmentRepository.countByStatus(ShipmentStatus.CANCELLED))
        SupportTicketStatus.entries.forEach { status ->
            assertEquals(6, supportTicketRepository.countByStatus(status), "工单状态 $status 数量不正确")
        }

        val admins = listOf("admin", "admin1").map { username ->
            assertNotNull(userRepository.findByUsername(username)).also { admin ->
                assertEquals(Role.ADMIN, admin.role)
                assertEquals(Status.ACTIVE, admin.status)
                assertTrue(admin.enabled)
                assertTrue(admin.emailVerified)
                assertTrue(passwordEncoder.matches("integration-password", admin.password))
            }
        }
        val sentinel = assertNotNull(userRepository.findByUsername("mock_customer_001"))
        assertEquals(2, admins.size)
        assertTrue(passwordEncoder.matches("integration-password", sentinel.password))
        assertEquals(
            180,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT CAST(created_at AS DATE)) FROM orders WHERE order_no LIKE 'MOCK-ORD-%'",
                Int::class.java,
            ),
        )

        val countsBeforeSecondRun = tableCounts()
        initializer.initializeMockData()
        assertEquals(countsBeforeSecondRun, tableCounts())
    }

    private fun tableCounts(): List<Long> = listOf(
        userRepository.count(),
        productRepository.count(),
        orderRepository.count(),
        orderItemRepository.count(),
        shipmentRepository.count(),
        shipmentItemRepository.count(),
        supportTicketRepository.count(),
        supportTicketMessageRepository.count(),
    )
}
