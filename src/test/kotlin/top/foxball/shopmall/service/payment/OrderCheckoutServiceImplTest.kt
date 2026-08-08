package top.foxball.shopmall.service.payment

import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderIdempotency
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.IdempotencyKeyInvalidException
import top.foxball.shopmall.repository.OrderIdempotencyRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.service.impl.OrderCheckoutServiceImpl
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentClientAction
import top.foxball.shopmall.service.payMent.PaymentCreateRequest
import top.foxball.shopmall.service.payMent.PaymentProviderError
import top.foxball.shopmall.service.payMent.PaymentProviderException
import top.foxball.shopmall.service.payMent.PaymentProviderId
import top.foxball.shopmall.service.payMent.PaymentStatus
import top.foxball.shopmall.service.payMent.PaymentTransaction
import top.foxball.shopmall.service.payMent.stripe.StripeCheckoutSession
import top.foxball.shopmall.service.payMent.stripe.StripeProperties
import top.foxball.shopmall.service.payMent.stripe.StripeService
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
class OrderCheckoutServiceImplTest {
    private val repository = mock(OrderRepository::class.java)
    private val idempotencyRepository = mock(OrderIdempotencyRepository::class.java)
    private val stripeService = mock(StripeService::class.java)
    private val clock = MutableClock(Instant.parse("2026-07-28T08:00:00Z"))
    private val properties = StripeProperties(
        secretKey = "sk_test_valid",
        webhookSecret = "whsec_test_valid",
        webhook = StripeProperties.WebhookProperties(URI("http://localhost:8080/webhook")),
        checkout = StripeProperties.CheckoutProperties(URI("http://localhost:3000")),
    )
    private val service = OrderCheckoutServiceImpl(
        repository,
        idempotencyRepository,
        stripeService,
        properties,
        clock,
        transactionManager(),
    )
    private val issuedKey = "key-order-7"

    private fun stubKeyBinding(order: OrderEntity) {
        `when`(idempotencyRepository.findByCustomerIdAndOrderNo(order.customerId, order.orderNo))
            .thenReturn(
                OrderIdempotency(
                    customerId = order.customerId,
                    idempotencyKey = issuedKey,
                    requestHash = "hash",
                    orderNo = order.orderNo,
                ),
            )
    }

    @Test
    fun `retry after Stripe response timeout uses identical create request`() {
        val order = pendingOrder(expiresAt = Instant.parse("2026-07-28T08:25:00Z"))
        stubKeyBinding(order)
        val expectedRequest = paymentCreateRequest(order)
        `when`(repository.findByOrderNoAndCustomerId(order.orderNo, order.customerId)).thenReturn(order)
        `when`(repository.attachStripeCheckoutSession(10, "cs_recovered", "pi_recovered")).thenReturn(1)
        `when`(stripeService.createPayment(expectedRequest))
            .thenThrow(
                PaymentProviderException(
                    PaymentProviderId("stripe"),
                    PaymentProviderError.TEMPORARILY_UNAVAILABLE,
                    true,
                    "response timed out",
                ),
            )
            .thenReturn(
                checkoutTransaction(
                    "cs_recovered",
                    "pi_recovered",
                    "https://checkout.stripe.com/c/pay/cs_recovered",
                ),
            )

        assertFailsWith<PaymentProviderException> {
            service.openCheckout(order.customerId, order.orderNo, issuedKey)
        }
        clock.current = Instant.parse("2026-07-28T08:10:00Z")
        val result = service.openCheckout(order.customerId, order.orderNo, issuedKey)

        assertNull(expectedRequest.expiresAt)
        assertEquals("https://checkout.stripe.com/c/pay/cs_recovered", result.checkoutUrl)
        verify(stripeService, times(2)).createPayment(expectedRequest)
        verify(repository).attachStripeCheckoutSession(10, "cs_recovered", "pi_recovered")
    }

    @Test
    fun `conditional binding loser returns the winning checkout Session`() {
        val order = pendingOrder(expiresAt = Instant.parse("2026-07-28T09:00:00Z"))
        stubKeyBinding(order)
        val winner = pendingOrder(expiresAt = order.expiresAt!!).apply {
            stripeCheckoutSessionId = "cs_winner"
            paymentIntentId = "pi_winner"
        }
        `when`(repository.findByOrderNoAndCustomerId(order.orderNo, order.customerId)).thenReturn(order)
        `when`(stripeService.createPayment(paymentCreateRequest(order))).thenReturn(
            checkoutTransaction("cs_loser", "pi_loser", "https://checkout.stripe.com/c/pay/cs_loser"),
        )
        `when`(repository.attachStripeCheckoutSession(10, "cs_loser", "pi_loser")).thenReturn(0)
        `when`(repository.findById(10)).thenReturn(Optional.of(winner))
        `when`(stripeService.expireCheckoutSession("cs_loser")).thenReturn(
            StripeCheckoutSession("cs_loser", "pi_loser", null, "expired", null),
        )
        `when`(stripeService.retrieveCheckoutSession("cs_winner")).thenReturn(
            StripeCheckoutSession(
                "cs_winner",
                "pi_winner",
                "https://checkout.stripe.com/c/pay/cs_winner",
                "open",
                null,
            ),
        )

        val result = service.openCheckout(order.customerId, order.orderNo, issuedKey)

        assertEquals("https://checkout.stripe.com/c/pay/cs_winner", result.checkoutUrl)
        verify(stripeService).expireCheckoutSession("cs_loser")
        verify(stripeService).retrieveCheckoutSession("cs_winner")
    }

    @Test
    fun `checkout rejects when the issued key does not match the order binding`() {
        val order = pendingOrder(expiresAt = Instant.parse("2026-07-28T08:25:00Z"))
        stubKeyBinding(order)

        assertFailsWith<IdempotencyKeyInvalidException> {
            service.openCheckout(order.customerId, order.orderNo, "another-key")
        }

        verify(idempotencyRepository).findByCustomerIdAndOrderNo(order.customerId, order.orderNo)
    }

    @Test
    fun `checkout rejects orders without any key binding`() {
        val order = pendingOrder(expiresAt = Instant.parse("2026-07-28T08:25:00Z"))
        `when`(idempotencyRepository.findByCustomerIdAndOrderNo(order.customerId, order.orderNo))
            .thenReturn(null)

        assertFailsWith<IdempotencyKeyInvalidException> {
            service.openCheckout(order.customerId, order.orderNo, issuedKey)
        }
    }

    @Test
    fun `checkout rejects another customers order after its key binding is checked`() {
        val foreignOrder = pendingOrder(expiresAt = Instant.parse("2026-07-28T08:25:00Z")).apply {
            customerId = 8
        }
        `when`(idempotencyRepository.findByCustomerIdAndOrderNo(7, foreignOrder.orderNo))
            .thenReturn(
                OrderIdempotency(
                    customerId = 7,
                    idempotencyKey = issuedKey,
                    requestHash = "hash",
                    orderNo = foreignOrder.orderNo,
                ),
            )
        `when`(repository.findByOrderNoAndCustomerId(foreignOrder.orderNo, 7)).thenReturn(null)
        `when`(repository.findByOrderNo(foreignOrder.orderNo)).thenReturn(foreignOrder)

        assertFailsWith<ForbiddenException> {
            service.openCheckout(7, foreignOrder.orderNo, issuedKey)
        }
    }

    private fun pendingOrder(expiresAt: Instant) = OrderEntity(
        id = 10,
        orderNo = "ORD-CHECKOUT-1",
        customerId = 7,
        status = OrderStatus.PENDING_PAYMENT,
        itemsSubtotal = BigDecimal("29.99"),
        totalAmount = BigDecimal("29.99"),
        currency = "USD",
        expiresAt = expiresAt,
    )

    private fun checkoutTransaction(sessionId: String, paymentIntentId: String, url: String) = PaymentTransaction(
        providerPaymentId = paymentIntentId,
        amount = PaymentAmount(BigDecimal("29.99"), "USD"),
        status = PaymentStatus.PENDING,
        clientAction = PaymentClientAction.Redirect(URI(url)),
        checkoutReference = sessionId,
        rawStatus = "open",
    )

    private fun paymentCreateRequest(order: OrderEntity) = PaymentCreateRequest(
        merchantPaymentId = order.orderNo,
        amount = PaymentAmount(order.totalAmount, order.currency),
        idempotencyKey = "${order.orderNo}:checkout-session",
        description = "Order ${order.orderNo}",
        returnUrl = URI(
            "http://localhost:3000/orders/${order.orderNo}/payment/result" +
                "?session_id=%7BCHECKOUT_SESSION_ID%7D",
        ),
        cancelUrl = URI("http://localhost:3000/orders/${order.orderNo}/payment/cancelled"),
        metadata = mapOf("orderNo" to order.orderNo),
    )

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
    }

    private fun transactionManager() = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }
}
