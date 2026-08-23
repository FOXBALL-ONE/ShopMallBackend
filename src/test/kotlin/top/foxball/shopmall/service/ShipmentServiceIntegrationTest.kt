package top.foxball.shopmall.service

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.AllocationStatus
import top.foxball.shopmall.entity.jdbc.NormalizedTrackingStatus
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.ShipmentTrack
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.ShipmentStatusException
import top.foxball.shopmall.logistics.TrackingEvent
import top.foxball.shopmall.repository.LogisticsIdempotencyRepository
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentTrackRepository
import top.foxball.shopmall.repository.UserRepository
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentServiceIntegrationTest @Autowired constructor(
    private val shipmentService: ShipmentService,
    private val userRepository: UserRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val shipmentItemRepository: ShipmentItemRepository,
    private val shipmentTrackRepository: ShipmentTrackRepository,
    private val logisticsIdempotencyRepository: LogisticsIdempotencyRepository,
) {
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

    @Test
    fun `shipment is logically deleted before it can be permanently deleted`() {
        val fixture = createFixture("delete-lifecycle")
        val created = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-DELETE",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-delete",
        )
        val shipmentId = requireNotNull(created.shipment.id)
        created.shipment.status = ShipmentStatus.DELIVERED
        shipmentRepository.saveAndFlush(created.shipment)
        shipmentTrackRepository.saveAndFlush(
            ShipmentTrack(
                shipment = created.shipment,
                carrierEventId = "delete-track",
                statusCode = "IN_TRANSIT",
                normalizedStatus = NormalizedTrackingStatus.IN_TRANSIT,
                source = TrackSource.MANUAL,
                occurredAt = Instant.parse("2026-08-01T00:00:00Z"),
            ),
        )

        val deleted = shipmentService.deleteShipment(created.shipment.shipmentNo, fixture.adminId)

        assertEquals(ShipmentStatus.DELETED, deleted.status)
        assertEquals(
            AllocationStatus.RELEASED,
            shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId).single().allocationStatus,
        )
        assertEquals(emptyList(), shipmentService.listCustomer(fixture.orderNo, 100L))

        shipmentService.permanentlyDeleteShipment(created.shipment.shipmentNo, fixture.adminId)

        assertFalse(shipmentRepository.existsById(shipmentId))
        assertEquals(emptyList(), shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId))
        assertEquals(emptyList(), shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId))
        assertNull(
            logisticsIdempotencyRepository.findByActorIdAndOperationAndIdempotencyKey(
                fixture.adminId,
                "CREATE_SHIPMENT",
                "create-delete",
            ),
        )
    }

    @Test
    fun `logically deleted shipment rejects status changes including idempotent cancel replay`() {
        val fixture = createFixture("deleted-status-guard")
        val created = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-DELETED-STATUS-GUARD",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-deleted-status-guard",
        )
        shipmentService.cancelShipment(
            shipmentNo = created.shipment.shipmentNo,
            reason = "cancel before deletion",
            adminId = fixture.adminId,
            idempotencyKey = "cancel-before-deletion",
        )
        shipmentService.deleteShipment(created.shipment.shipmentNo, fixture.adminId)
        shipmentService.handleTrackingEvent(
            CarrierCode.MANUAL,
            TrackingEvent(
                trackingNo = "TRACK-DELETED-STATUS-GUARD",
                carrierEventId = "deleted-status-guard-event",
                statusCode = "IN_TRANSIT",
                normalizedStatus = NormalizedTrackingStatus.IN_TRANSIT,
                location = "warehouse",
                description = "must be ignored",
                occurredAt = Instant.parse("2026-08-20T12:00:00Z"),
                raw = null,
            ),
            TrackSource.WEBHOOK,
        )

        val replayError = assertFailsWith<ShipmentStatusException> {
            shipmentService.cancelShipment(
                shipmentNo = created.shipment.shipmentNo,
                reason = "cancel before deletion",
                adminId = fixture.adminId,
                idempotencyKey = "cancel-before-deletion",
            )
        }
        assertEquals("已逻辑删除的运单不能再变更状态", replayError.message)
        assertFailsWith<ShipmentStatusException> {
            shipmentService.dispatchShipment(
                shipmentNo = created.shipment.shipmentNo,
                note = null,
                adminId = fixture.adminId,
                idempotencyKey = "dispatch-after-deletion",
            )
        }
        assertFailsWith<ShipmentStatusException> {
            shipmentService.markManualDelivered(
                shipmentNo = created.shipment.shipmentNo,
                occurredAt = null,
                reason = "deliver after deletion",
                adminId = fixture.adminId,
                idempotencyKey = "deliver-after-deletion",
            )
        }
        assertEquals(
            ShipmentStatus.DELETED,
            shipmentRepository.findById(requireNotNull(created.shipment.id)).get().status,
        )
        assertEquals(
            emptyList(),
            shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(
                requireNotNull(created.shipment.id),
            ),
        )
    }

    @Test
    fun `permanent shipment deletion rejects a shipment that is not logically deleted`() {
        val fixture = createFixture("delete-guard")
        val created = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-DELETE-GUARD",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-delete-guard",
        )

        assertFailsWith<top.foxball.shopmall.handler.ShipmentStatusException> {
            shipmentService.deleteShipment(created.shipment.shipmentNo, fixture.adminId)
        }
        assertFailsWith<top.foxball.shopmall.handler.ShipmentStatusException> {
            shipmentService.permanentlyDeleteShipment(created.shipment.shipmentNo, fixture.adminId)
        }
        assertEquals(ShipmentStatus.LABEL_CREATED, shipmentRepository.findById(requireNotNull(created.shipment.id)).get().status)
    }

    @Test
    fun `admin shipment list handles absent and case-insensitive tracking number filters`() {
        val fixture = createFixture("admin-list")
        val created = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-ADMIN-LIST",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-admin-list",
        )

        val unfiltered = shipmentService.listAdmin(fixture.adminId, AdminShipmentQuery(trackingNo = "   "))
        val filtered = shipmentService.listAdmin(
            fixture.adminId,
            AdminShipmentQuery(trackingNo = "track-admin"),
        )

        assertEquals(listOf(created.shipment.id), unfiltered.content.map { it.shipment.id })
        assertEquals(listOf(created.shipment.id), filtered.content.map { it.shipment.id })
    }

    @Test
    fun `customer cannot read another customers shipment`() {
        val fixture = createFixture("customer-ownership")
        val created = shipmentService.createShipment(
            orderNo = fixture.orderNo,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-CUSTOMER-OWNERSHIP",
            orderItemIds = listOf(fixture.orderItemId),
            quantities = listOf(1),
            note = null,
            adminId = fixture.adminId,
            idempotencyKey = "create-customer-ownership",
        )

        assertFailsWith<ForbiddenException> {
            shipmentService.getCustomer(fixture.orderNo, created.shipment.shipmentNo, 101L)
        }
        assertFailsWith<ForbiddenException> {
            shipmentService.trackByTrackingNumber(CarrierCode.MANUAL, "TRACK-CUSTOMER-OWNERSHIP", 101L)
        }
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
                variantId = 10,
                sku = "SHIPMENT-TEST-SKU",
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
