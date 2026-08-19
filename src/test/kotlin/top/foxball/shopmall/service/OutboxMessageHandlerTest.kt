package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.OutboxEventRepository
import top.foxball.shopmall.service.impl.OutboxMessageHandler
import top.foxball.shopmall.service.impl.ShipmentOutboxProcessor
import top.foxball.shopmall.service.payMent.PaymentStatus
import top.foxball.shopmall.service.payMent.stripe.StripeService
import top.foxball.shopmall.service.payMent.stripe.StripeCheckoutSession
import top.foxball.shopmall.shared.PaymentOperationBusyException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OutboxMessageHandlerTest {
    private val repository = mock(OutboxEventRepository::class.java)
    private val orderRepository = mock(OrderRepository::class.java)
    private val paymentService = mock(OrderPaymentService::class.java)
    private val orderMailService = mock(OrderMailService::class.java)
    private val shipmentOutboxProcessor = mock(ShipmentOutboxProcessor::class.java)
    private val stripeService = mock(StripeService::class.java)
    private val transactionManager = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }
    private val clock = Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), ZoneOffset.UTC)
    private val handler = OutboxMessageHandler(
        repository,
        orderRepository,
        paymentService,
        orderMailService,
        shipmentOutboxProcessor,
        stripeService,
        OrderProperties(outboxMaxAttempts = 2),
        clock,
        transactionManager,
    )

    @Test
    fun `paid order receipt is resolved before email and outbox acknowledgement`() {
        val event = OutboxEvent(id = 4, status = OutboxEvent.Status.SENT)
        val order = OrderEntity(
            id = 19,
            orderNo = "ORDER-19",
            status = OrderStatus.PAID,
            paymentIntentId = "pi_order_19",
        )
        `when`(repository.findById(4)).thenReturn(Optional.of(event))
        `when`(orderRepository.findById(19)).thenReturn(Optional.of(order))
        `when`(stripeService.retrievePaymentReceiptUrl("pi_order_19"))
            .thenReturn("https://pay.stripe.com/receipts/payment-19")

        handler.handle(4, "ORDER", 19, "PAID")

        verify(stripeService).retrievePaymentReceiptUrl("pi_order_19")
        verify(orderMailService).sendPaymentConfirmation(
            19,
            "https://pay.stripe.com/receipts/payment-19",
        )
        assertEquals(OutboxEvent.Status.ACKNOWLEDGED, event.status)
        assertEquals(clock.instant(), event.acknowledgedAt)
    }

    @Test
    fun `paid order receipt failure leaves outbox unacknowledged`() {
        val event = OutboxEvent(id = 9, status = OutboxEvent.Status.SENT)
        val order = OrderEntity(
            id = 22,
            orderNo = "ORDER-22",
            status = OrderStatus.PAID,
            paymentIntentId = "pi_order_22",
        )
        `when`(repository.findById(9)).thenReturn(Optional.of(event))
        `when`(orderRepository.findById(22)).thenReturn(Optional.of(order))
        `when`(stripeService.retrievePaymentReceiptUrl("pi_order_22"))
            .thenThrow(IllegalStateException("receipt unavailable"))

        assertFailsWith<IllegalStateException> {
            handler.handle(9, "ORDER", 22, "PAID")
        }

        verify(orderMailService, never()).sendPaymentConfirmation(
            22,
            "https://pay.stripe.com/receipts/payment-22",
        )
        assertEquals(OutboxEvent.Status.SENT, event.status)
        assertEquals(null, event.acknowledgedAt)
    }

    @Test
    fun `paid order email failure leaves outbox unacknowledged`() {
        val event = OutboxEvent(id = 11, status = OutboxEvent.Status.SENT)
        val order = OrderEntity(
            id = 24,
            orderNo = "ORDER-24",
            status = OrderStatus.PAID,
            paymentIntentId = "pi_order_24",
        )
        val receiptUrl = "https://pay.stripe.com/receipts/payment-24"
        `when`(repository.findById(11)).thenReturn(Optional.of(event))
        `when`(orderRepository.findById(24)).thenReturn(Optional.of(order))
        `when`(stripeService.retrievePaymentReceiptUrl("pi_order_24")).thenReturn(receiptUrl)
        doThrow(IllegalStateException("SMTP unavailable"))
            .`when`(orderMailService).sendPaymentConfirmation(24, receiptUrl)

        assertFailsWith<IllegalStateException> {
            handler.handle(11, "ORDER", 24, "PAID")
        }

        assertEquals(OutboxEvent.Status.SENT, event.status)
        assertEquals(null, event.acknowledgedAt)
    }

    @Test
    fun `paid outbox without a PaymentIntent sends confirmation without receipt and is acknowledged`() {
        val event = OutboxEvent(id = 10, status = OutboxEvent.Status.SENT)
        val order = OrderEntity(
            id = 23,
            orderNo = "ORDER-23",
            status = OrderStatus.PAID,
        )
        `when`(repository.findById(10)).thenReturn(Optional.of(event))
        `when`(orderRepository.findById(23)).thenReturn(Optional.of(order))

        handler.handle(10, "ORDER", 23, "PAID")

        verify(stripeService, never()).retrievePaymentReceiptUrl(org.mockito.ArgumentMatchers.anyString())
        verify(orderMailService).sendPaymentConfirmation(23, null)
        assertEquals(OutboxEvent.Status.ACKNOWLEDGED, event.status)
        assertEquals(clock.instant(), event.acknowledgedAt)
    }

    @Test
    fun `paid outbox resolves and stores PaymentIntent from Checkout Session`() {
        val event = OutboxEvent(id = 12, status = OutboxEvent.Status.SENT)
        val order = OrderEntity(
            id = 25,
            orderNo = "ORDER-25",
            status = OrderStatus.PAID,
            stripeCheckoutSessionId = "cs_order_25",
        )
        val receiptUrl = "https://pay.stripe.com/receipts/payment-25"
        `when`(repository.findById(12)).thenReturn(Optional.of(event))
        `when`(orderRepository.findById(25)).thenReturn(Optional.of(order))
        `when`(stripeService.retrieveCheckoutSession("cs_order_25")).thenReturn(
            StripeCheckoutSession(
                id = "cs_order_25",
                paymentIntentId = "pi_order_25",
                url = null,
                status = "complete",
                expiresAt = null,
                paymentStatus = "paid",
                collectionStatus = PaymentStatus.SUCCEEDED,
            ),
        )
        `when`(stripeService.retrievePaymentReceiptUrl("pi_order_25")).thenReturn(receiptUrl)

        handler.handle(12, "ORDER", 25, "PAID")

        verify(orderRepository).attachPaymentIntentToStripeCheckoutSession("cs_order_25", "pi_order_25")
        verify(orderMailService).sendPaymentConfirmation(25, receiptUrl)
        assertEquals(OutboxEvent.Status.ACKNOWLEDGED, event.status)
        assertEquals(clock.instant(), event.acknowledgedAt)
    }

    @Test
    fun `payment compensation is acknowledged only after payment handler succeeds`() {
        val event = OutboxEvent(id = 5, status = OutboxEvent.Status.SENT)
        `when`(repository.findById(5)).thenReturn(Optional.of(event))

        handler.handle(5, "ORDER", 20, "PAYMENT_CANCEL_OR_REFUND")

        verify(paymentService).reconcileCancellation(20)
        assertEquals(OutboxEvent.Status.ACKNOWLEDGED, event.status)
        assertEquals(clock.instant(), event.acknowledgedAt)
    }

    @Test
    fun `payment compensation remains retryable when coordinator lock is busy`() {
        val event = OutboxEvent(id = 8, status = OutboxEvent.Status.SENT)
        `when`(repository.findById(8)).thenReturn(Optional.of(event))
        doThrow(PaymentOperationBusyException("pi_locked"))
            .`when`(paymentService).reconcileCancellation(21)

        assertFailsWith<PaymentOperationBusyException> {
            handler.handle(8, "ORDER", 21, "PAYMENT_CANCEL_OR_REFUND")
        }

        assertEquals(OutboxEvent.Status.SENT, event.status)
        assertEquals(null, event.acknowledgedAt)
    }

    @Test
    fun `shipment command is delegated before outbox acknowledgement`() {
        val event = OutboxEvent(id = 7, status = OutboxEvent.Status.SENT)
        `when`(repository.findById(7)).thenReturn(Optional.of(event))

        handler.handle(7, "SHIPMENT", 30, "SHIPMENT_LABEL_REQUESTED")

        verify(shipmentOutboxProcessor).handle(30, "SHIPMENT_LABEL_REQUESTED")
        assertEquals(OutboxEvent.Status.ACKNOWLEDGED, event.status)
        assertEquals(clock.instant(), event.acknowledgedAt)
    }

    @Test
    fun `consumer failures retry before moving to replay state`() {
        val event = OutboxEvent(id = 6, status = OutboxEvent.Status.SENT)
        `when`(repository.findById(6)).thenReturn(Optional.of(event))

        assertFalse(handler.recordFailure(6))
        assertEquals(OutboxEvent.Status.PENDING, event.status)
        assertEquals(1, event.attempts)

        assertTrue(handler.recordFailure(6))
        assertEquals(OutboxEvent.Status.NEEDS_REPLAY, event.status)
        assertEquals(2, event.attempts)
    }
}
