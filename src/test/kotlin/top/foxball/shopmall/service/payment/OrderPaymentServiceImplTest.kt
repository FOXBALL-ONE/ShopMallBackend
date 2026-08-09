package top.foxball.shopmall.service.payment

import com.stripe.model.Event
import com.stripe.model.EventDataObjectDeserializer
import com.stripe.model.StripeObject
import com.stripe.model.checkout.Session
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.StripeWebhookEventRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminOrderPaymentQuerySource
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.impl.OrderPaymentServiceImpl
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentQueryRequest
import top.foxball.shopmall.service.payMent.PaymentStatus
import top.foxball.shopmall.service.payMent.PaymentTransaction
import top.foxball.shopmall.service.payMent.stripe.StripeCheckoutSession
import top.foxball.shopmall.service.payMent.stripe.StripeService
import top.foxball.shopmall.shared.PaymentIntentCoordinator
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderPaymentServiceImplTest {
    private val orderRepository = mock(OrderRepository::class.java)
    private val orderItemRepository = mock(OrderItemRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val webhookRepository = mock(StripeWebhookEventRepository::class.java)
    private val eventPublisher = mock(DomainEventPublisher::class.java)
    private val coordinator = mock(PaymentIntentCoordinator::class.java)
    private val adminAccessService = mock(AdminAccessService::class.java)
    private val stripeService = mock(StripeService::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-07-28T08:00:00Z"), ZoneOffset.UTC)
    private val service = OrderPaymentServiceImpl(
        orderRepository = orderRepository,
        orderItemRepository = orderItemRepository,
        productRepository = productRepository,
        webhookEventRepository = webhookRepository,
        eventPublisher = eventPublisher,
        paymentIntentCoordinator = coordinator,
        adminAccessService = adminAccessService,
        stripeService = stripeService,
        clock = clock,
    )

    init {
        `when`(stripeService.provider).thenReturn(top.foxball.shopmall.service.payMent.PaymentProviderId("stripe"))
    }

    @Test
    fun `duplicate webhook event stops after idempotency claim`() {
        val event = mock(Event::class.java)
        `when`(event.id).thenReturn("evt_duplicate")
        `when`(event.type).thenReturn("checkout.session.completed")
        `when`(webhookRepository.claim("evt_duplicate", "checkout.session.completed")).thenReturn(0)

        service.handleWebhookEvent(event)

        verifyNoInteractions(orderRepository, orderItemRepository, productRepository, eventPublisher)
    }

    @Test
    fun `supported webhook deserialization failure is retryable`() {
        val event = checkoutEvent(
            id = "evt_unreadable",
            type = "checkout.session.completed",
            eventObject = null,
        )
        `when`(webhookRepository.claim("evt_unreadable", "checkout.session.completed")).thenReturn(1)

        assertFailsWith<IllegalStateException> { service.handleWebhookEvent(event) }

        verifyNoInteractions(orderRepository, orderItemRepository, productRepository, eventPublisher)
    }

    @Test
    fun `webhook without a bound Session can be retried`() {
        val session = checkoutSession("cs_not_bound", "pi_not_bound", "paid")
        val event = checkoutEvent("evt_not_bound", "checkout.session.completed", session)
        `when`(webhookRepository.claim("evt_not_bound", "checkout.session.completed")).thenReturn(1)
        `when`(orderRepository.findByStripeCheckoutSessionId("cs_not_bound")).thenReturn(null)

        repeat(2) {
            assertFailsWith<IllegalStateException> { service.handleWebhookEvent(event) }
        }

        verify(webhookRepository, times(2)).claim("evt_not_bound", "checkout.session.completed")
        verify(orderRepository, times(2)).findByStripeCheckoutSessionId("cs_not_bound")
    }

    @Test
    fun `timeout winning over paid webhook preserves cancellation and schedules refund`() {
        val order = pendingOrder()
        val expired = checkoutEvent(
            "evt_expired",
            "checkout.session.expired",
            checkoutSession("cs_order", "pi_order", "unpaid"),
        )
        val paid = checkoutEvent(
            "evt_paid",
            "checkout.session.async_payment_succeeded",
            checkoutSession("cs_order", "pi_order", "paid"),
        )
        `when`(webhookRepository.claim("evt_expired", "checkout.session.expired")).thenReturn(1)
        `when`(webhookRepository.claim("evt_paid", "checkout.session.async_payment_succeeded")).thenReturn(1)
        `when`(orderRepository.findByStripeCheckoutSessionId("cs_order")).thenReturn(order)
        `when`(
            orderRepository.markCancelled(
                10,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED,
                clock.instant(),
                "CHECKOUT_SESSION_EXPIRED",
            ),
        ).thenReturn(1)
        `when`(orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(10)).thenReturn(emptyList())
        `when`(orderRepository.markPaid(10, OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, clock.instant()))
            .thenReturn(0)
        `when`(orderRepository.findStatusById(10)).thenReturn(OrderStatus.CANCELLED)

        service.handleWebhookEvent(expired)
        service.handleWebhookEvent(paid)

        verify(eventPublisher).publishInTx("ORDER", 10, "TIMEOUT", "{\"orderId\":10}")
        verify(eventPublisher).publishInTx("ORDER", 10, "CANCELLED", "{\"orderId\":10}")
        verify(eventPublisher).publishInTx("ORDER", 10, "PAYMENT_CONFLICT_REFUND", "{\"orderId\":10}")
        verify(productRepository, never()).incrementSales(10, 1)
    }

    @Test
    fun `cancellation and conflict compensation share one refund idempotency key`() {
        val order = pendingOrder().apply {
            stripeCheckoutSessionId = "cs_order"
            paymentIntentId = "pi_order"
        }
        `when`(orderRepository.findById(10)).thenReturn(Optional.of(order))

        service.reconcileCancellation(10)
        service.reconcileConflictRefund(10)

        val key = "ORD-PAYMENT-1:cancelled-order-refund"
        verify(coordinator).cancelOrRefund("cs_order", "pi_order", key)
        verify(coordinator).refund("pi_order", key)
    }

    @Test
    fun `admin query uses PaymentIntent and reports matching collected amount`() {
        val order = pendingOrder().apply {
            paymentIntentId = "pi_order"
            stripeCheckoutSessionId = "cs_order"
        }
        val transaction = PaymentTransaction(
            providerPaymentId = "pi_order",
            amount = PaymentAmount(BigDecimal("29.99"), "USD"),
            status = PaymentStatus.SUCCEEDED,
            rawStatus = "succeeded",
        )
        `when`(orderRepository.findByOrderNo(order.orderNo)).thenReturn(order)
        `when`(stripeService.queryPayment(PaymentQueryRequest("pi_order"))).thenReturn(transaction)

        val result = service.queryAdminPaymentStatus(99, order.orderNo)

        assertEquals(AdminOrderPaymentQuerySource.PAYMENT_INTENT, result.querySource)
        assertEquals(PaymentStatus.SUCCEEDED, result.providerStatus)
        assertEquals(true, result.amountMatchesOrder)
        assertEquals("succeeded", result.paymentIntentStatus)
        verify(adminAccessService).requireAdmin(99)
        verify(stripeService).queryPayment(PaymentQueryRequest("pi_order"))
    }

    @Test
    fun `admin query falls back to Checkout Session before a PaymentIntent exists`() {
        val order = pendingOrder().apply { stripeCheckoutSessionId = "cs_order" }
        val session = StripeCheckoutSession(
            id = "cs_order",
            paymentIntentId = null,
            url = "https://checkout.stripe.com/c/pay/cs_order",
            status = "open",
            expiresAt = Instant.parse("2026-07-28T08:30:00Z"),
            paymentStatus = "unpaid",
            amount = PaymentAmount(BigDecimal("29.99"), "USD"),
            collectionStatus = PaymentStatus.PENDING,
        )
        `when`(orderRepository.findByOrderNo(order.orderNo)).thenReturn(order)
        `when`(stripeService.retrieveCheckoutSession("cs_order")).thenReturn(session)

        val result = service.queryAdminPaymentStatus(99, order.orderNo)

        assertEquals(AdminOrderPaymentQuerySource.CHECKOUT_SESSION, result.querySource)
        assertEquals(PaymentStatus.PENDING, result.providerStatus)
        assertEquals("open", result.checkoutSessionStatus)
        assertEquals("unpaid", result.checkoutPaymentStatus)
        assertEquals(true, result.amountMatchesOrder)
    }

    @Test
    fun `admin query rejects an order without a Stripe payment reference`() {
        val order = pendingOrder()
        `when`(orderRepository.findByOrderNo(order.orderNo)).thenReturn(order)

        assertFailsWith<OrderStatusException> {
            service.queryAdminPaymentStatus(99, order.orderNo)
        }
    }

    private fun checkoutEvent(id: String, type: String, eventObject: StripeObject?): Event {
        val deserializer = mock(EventDataObjectDeserializer::class.java)
        `when`(deserializer.getObject()).thenReturn(Optional.ofNullable(eventObject))
        return mock(Event::class.java).also { event ->
            `when`(event.id).thenReturn(id)
            `when`(event.type).thenReturn(type)
            `when`(event.apiVersion).thenReturn("2025-12-15.clover")
            `when`(event.dataObjectDeserializer).thenReturn(deserializer)
        }
    }

    private fun checkoutSession(id: String, paymentIntentId: String, paymentStatus: String): Session =
        mock(Session::class.java).also { session ->
            `when`(session.id).thenReturn(id)
            `when`(session.paymentIntent).thenReturn(paymentIntentId)
            `when`(session.paymentStatus).thenReturn(paymentStatus)
        }

    private fun pendingOrder() = OrderEntity(
        id = 10,
        orderNo = "ORD-PAYMENT-1",
        customerId = 7,
        status = OrderStatus.PENDING_PAYMENT,
        itemsSubtotal = BigDecimal("29.99"),
        totalAmount = BigDecimal("29.99"),
    )
}
