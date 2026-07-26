package top.foxball.shopmall.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.UserRepository
import java.math.BigDecimal
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentServiceIntegrationTest @Autowired constructor(
    private val shipmentService: ShipmentService,
    private val userRepository: UserRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun createShipmentSequence() {
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS shipment_no_seq START WITH 1")
    }

    @Test
    fun `manual shipment is replayed idempotently and dispatches the order`() {
        val fixture = createFixture("dispatch")

        val first = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-DISPATCH",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-dispatch",
        )
        val replayed = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-DISPATCH",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-dispatch",
        )

        assertEquals(first.shipment.shipmentNo, replayed.shipment.shipmentNo)
        assertEquals(1, shipmentRepository.findAllByOrderIdOrderByCreatedAtAsc(fixture.orderId).size)

        shipmentService.dispatchShipment(
            shipmentNo = first.shipment.shipmentNo,
            note = "handed to carrier",
            adminId = fixture.adminId,
            idempotencyKey = "dispatch-1",
        )

        assertEquals(OrderStatus.SHIPPED, orderRepository.findStatusById(fixture.orderId))
    }

    @Test
    fun `cancelling manual shipment releases allocation for replacement`() {
        val fixture = createFixture("cancel")
        val first = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-CANCEL-1",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-cancel-1",
        )

        shipmentService.cancelShipment(
            shipmentNo = first.shipment.shipmentNo,
            reason = "replace damaged label",
            adminId = fixture.adminId,
            idempotencyKey = "cancel-1",
        )
        val replacement = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-CANCEL-2",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-cancel-2",
        )

        assertEquals(ShipmentStatus.LABEL_CREATED, replacement.shipment.status)
        assertEquals(2, shipmentRepository.findAllByOrderIdOrderByCreatedAtAsc(fixture.orderId).size)
    }

    private fun createFixture(suffix: String): Fixture {
        val admin = userRepository.saveAndFlush(
            User(
                email = "admin-$suffix@example.com",
                username = "admin-$suffix",
                password = "encoded-password",
                role = Role.ADMIN,
            ),
        )
        val order = orderRepository.saveAndFlush(
            OrderEntity(
                orderNo = "ORDER-${suffix.uppercase()}",
                customerId = 100,
                status = OrderStatus.PAID,
                itemsSubtotal = BigDecimal.TEN,
                totalAmount = BigDecimal.TEN,
                shippingAddress = OrderShippingAddress(
                    name = "Test User",
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
                productId = 1,
                productSnapshot = "{}",
                unitPrice = BigDecimal.TEN,
                quantity = 1,
                lineTotal = BigDecimal.TEN,
            ),
        )
        return Fixture(
            adminId = requireNotNull(admin.id),
            orderId = requireNotNull(order.id),
            orderNo = order.orderNo,
            orderItemId = requireNotNull(item.id),
        )
    }

    private data class Fixture(
        val adminId: Long,
        val orderId: Long,
        val orderNo: String,
        val orderItemId: Long,
    )
}
