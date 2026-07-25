package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.handler.CarrierException
import top.foxball.shopmall.logistics.CancelLabelRequest
import top.foxball.shopmall.logistics.CancelLabelResult
import top.foxball.shopmall.logistics.Carrier
import top.foxball.shopmall.logistics.CarrierCapabilities
import top.foxball.shopmall.logistics.CarrierRegistry
import top.foxball.shopmall.logistics.LabelRequest
import top.foxball.shopmall.logistics.LabelResponse
import top.foxball.shopmall.logistics.TrackingEvent
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.service.impl.ShipmentOutboxProcessor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShipmentOutboxProcessorTest {
    private val orderRepository = mock(OrderRepository::class.java)
    private val shipmentRepository = mock(ShipmentRepository::class.java)
    private val shipmentItemRepository = mock(ShipmentItemRepository::class.java)
    private val eventPublisher = mock(DomainEventPublisher::class.java)
    private val carrier = TestCarrier()
    private val carrierRegistry = CarrierRegistry(listOf(carrier), LogisticsProperties())
    private val clock = Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), ZoneOffset.UTC)
    private val transactionManager = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }
    private val processor = ShipmentOutboxProcessor(
        orderRepository,
        shipmentRepository,
        shipmentItemRepository,
        carrierRegistry,
        eventPublisher,
        transactionManager,
        clock,
    )

    @Test
    fun `remote label result updates pending shipment and publishes event`() {
        val shipment = shipment(ShipmentStatus.LABEL_PENDING)
        val item = ShipmentItem(
            id = 20,
            shipment = shipment,
            orderItemId = 30,
            orderItemSnapshot = "product",
            quantity = 2,
        )
        `when`(shipmentRepository.findById(10)).thenReturn(Optional.of(shipment))
        `when`(shipmentRepository.findByIdForUpdate(10)).thenReturn(shipment)
        `when`(shipmentItemRepository.findAllByShipment_IdOrderById(10)).thenReturn(listOf(item))
        `when`(orderRepository.lockById(5)).thenReturn(OrderEntity(id = 5, customerId = 1))
        carrier.labelResponse = LabelResponse("https://carrier.test/label", " track-10 ")

        processor.handle(10, "SHIPMENT_LABEL_REQUESTED")

        assertEquals(ShipmentStatus.LABEL_CREATED, shipment.status)
        assertEquals("track-10", shipment.trackingNo)
        assertEquals("TRACK-10", shipment.trackingNoNormalized)
        assertEquals("https://carrier.test/label", shipment.carrierLabelUrl)
        assertEquals("https://carrier.test/track/track-10", shipment.trackingUrl)
        assertEquals(2, carrier.lastLabelRequest?.items?.single()?.quantity)
        verify(eventPublisher).publishInTx(
            "SHIPMENT",
            10,
            "SHIPMENT_LABEL_CREATED",
            "{\"shipmentId\":10}",
        )
    }

    @Test
    fun `retryable remote cancellation remains pending and throws`() {
        val shipment = shipment(ShipmentStatus.CANCEL_PENDING)
        `when`(shipmentRepository.findById(10)).thenReturn(Optional.of(shipment))
        carrier.cancelResult = CancelLabelResult.RETRYABLE_FAILURE

        assertFailsWith<CarrierException> {
            processor.handle(10, "SHIPMENT_CANCEL_REQUESTED")
        }

        assertEquals(ShipmentStatus.CANCEL_PENDING, shipment.status)
    }

    @Test
    fun `successful remote cancellation releases allocation and publishes event`() {
        val shipment = shipment(ShipmentStatus.CANCEL_PENDING).apply { cancelReason = "CUSTOMER_REQUEST" }
        `when`(shipmentRepository.findById(10)).thenReturn(Optional.of(shipment))
        `when`(shipmentRepository.findByIdForUpdate(10)).thenReturn(shipment)
        `when`(orderRepository.lockById(5)).thenReturn(OrderEntity(id = 5, customerId = 1))
        `when`(
            shipmentItemRepository.releaseAllocatedByShipmentId(
                10,
                clock.instant(),
                "CUSTOMER_REQUEST",
            ),
        ).thenReturn(1)

        processor.handle(10, "SHIPMENT_CANCEL_REQUESTED")

        assertEquals(ShipmentStatus.CANCELLED, shipment.status)
        verify(eventPublisher).publishInTx(
            "SHIPMENT",
            10,
            "SHIPMENT_CANCELLED",
            "{\"shipmentId\":10}",
        )
    }

    private fun shipment(status: ShipmentStatus) = Shipment(
        id = 10,
        shipmentNo = "S-10",
        orderId = 5,
        carrierCode = CarrierCode.FOUR_PX,
        trackingNo = "TRACK-OLD",
        status = status,
        shippingAddress = OrderShippingAddress(
            name = "Recipient",
            phone = "+8613800000000",
            country = "CN",
            city = "Shanghai",
            address1 = "Road 1",
        ),
        createdBy = 1,
    )

    private class TestCarrier : Carrier {
        override val code = CarrierCode.FOUR_PX
        override val capabilities = CarrierCapabilities(remoteLabel = true, webhook = true, polling = true)
        var labelResponse = LabelResponse(null, "TRACK-10")
        var cancelResult = CancelLabelResult.CANCELLED_OR_NOT_FOUND
        var lastLabelRequest: LabelRequest? = null

        override fun createLabel(request: LabelRequest): LabelResponse {
            lastLabelRequest = request
            return labelResponse
        }

        override fun cancelLabel(request: CancelLabelRequest): CancelLabelResult = cancelResult

        override fun queryTracking(trackingNo: String): List<TrackingEvent> = emptyList()

        override fun parseWebhook(
            payload: ByteArray,
            headers: Map<String, List<String>>,
        ): List<TrackingEvent> = emptyList()

        override fun normalizeTrackingNo(trackingNo: String): String = trackingNo.uppercase()

        override fun trackingUrl(trackingNo: String): String = "https://carrier.test/track/$trackingNo"
    }
}
