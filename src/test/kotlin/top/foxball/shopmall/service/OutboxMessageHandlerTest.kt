package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.repository.OutboxEventRepository
import top.foxball.shopmall.service.impl.OutboxMessageHandler
import top.foxball.shopmall.service.impl.ShipmentOutboxProcessor
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
    private val paymentService = mock(OrderPaymentService::class.java)
    private val orderMailService = mock(OrderMailService::class.java)
    private val shipmentOutboxProcessor = mock(ShipmentOutboxProcessor::class.java)
    private val transactionManager = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }
    private val clock = Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), ZoneOffset.UTC)
    private val handler = OutboxMessageHandler(
        repository,
        paymentService,
        orderMailService,
        shipmentOutboxProcessor,
        OrderProperties(outboxMaxAttempts = 2),
        clock,
        transactionManager,
    )

    @Test
    fun `paid order email is delegated before outbox acknowledgement`() {
        val event = OutboxEvent(id = 4, status = OutboxEvent.Status.SENT)
        `when`(repository.findById(4)).thenReturn(Optional.of(event))

        handler.handle(4, "ORDER", 19, "PAID")

        verify(orderMailService).sendPaymentConfirmation(19)
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
