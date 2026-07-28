package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.service.OrderCheckoutService
import top.foxball.shopmall.service.OrderCheckoutView
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentClientAction
import top.foxball.shopmall.service.payMent.PaymentCreateRequest
import top.foxball.shopmall.service.payMent.PaymentProviderError
import top.foxball.shopmall.service.payMent.PaymentProviderException
import top.foxball.shopmall.service.payMent.stripe.StripeProperties
import top.foxball.shopmall.service.payMent.stripe.StripeService
import java.net.URI
import java.time.Clock
import java.time.Instant

/**
 * 订单与 Stripe Checkout Session 的协调器。
 *
 * 数据库读写使用短事务；创建和查询 Stripe 会话始终发生在事务之外，避免外部网络调用占用订单锁。
 */
@Service
class OrderCheckoutServiceImpl(
    private val orderRepository: OrderRepository,
    private val stripeService: StripeService,
    private val stripeProperties: StripeProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : OrderCheckoutService {
    private val transactions = TransactionTemplate(transactionManager)

    override fun openCheckout(customerId: Long, orderNo: String): OrderCheckoutView {
        val candidate = requireNotNull(transactions.execute {
            loadCandidate(customerId, orderNo)
        })
        candidate.sessionId?.let { return reuseCheckout(candidate, it) }

        val transaction = stripeService.createPayment(
            PaymentCreateRequest(
                merchantPaymentId = candidate.orderNo,
                amount = PaymentAmount(candidate.totalAmount, candidate.currency),
                idempotencyKey = "${candidate.orderNo}:checkout-session",
                description = "Order ${candidate.orderNo}",
                returnUrl = successUrl(candidate.orderNo),
                cancelUrl = cancelledUrl(candidate.orderNo),
                metadata = mapOf("orderNo" to candidate.orderNo),
            ),
        )
        val sessionId = requireNotNull(transaction.checkoutReference) {
            "Stripe Checkout payment did not return a session reference"
        }
        val checkoutUrl = (transaction.clientAction as? PaymentClientAction.Redirect)?.url?.toString()
            ?: throw PaymentProviderException(
                provider = stripeService.provider,
                error = PaymentProviderError.UNKNOWN,
                retryable = false,
                message = "Stripe Checkout payment did not return a redirect URL",
            )

        val binding = requireNotNull(transactions.execute {
            bindCheckoutSession(candidate.orderId, sessionId, transaction.providerPaymentId)
        })
        if (binding.attached) {
            return OrderCheckoutView(candidate.orderNo, OrderStatus.PENDING_PAYMENT, checkoutUrl, candidate.expiresAt)
        }

        val winnerSessionId = binding.sessionId
        if (winnerSessionId != null) {
            if (winnerSessionId == sessionId) {
                return OrderCheckoutView(candidate.orderNo, OrderStatus.PENDING_PAYMENT, checkoutUrl, candidate.expiresAt)
            }
            stripeService.expireCheckoutSession(sessionId)
            return reuseCheckout(candidate, winnerSessionId)
        }

        stripeService.expireCheckoutSession(sessionId)
        throw OrderStatusException("订单状态已变化，无法创建支付会话")
    }

    private fun reuseCheckout(candidate: CheckoutCandidate, sessionId: String): OrderCheckoutView {
        val session = stripeService.retrieveCheckoutSession(sessionId)
        if (session.paymentIntentId != null) {
            transactions.executeWithoutResult {
                orderRepository.attachPaymentIntentToStripeCheckoutSession(session.id, session.paymentIntentId)
            }
        }
        if (session.status != "open" || session.url.isNullOrBlank()) {
            throw OrderStatusException("当前支付会话不可继续使用")
        }
        return OrderCheckoutView(
            orderNo = candidate.orderNo,
            status = OrderStatus.PENDING_PAYMENT,
            checkoutUrl = session.url,
            expiresAt = candidate.expiresAt,
        )
    }

    private fun loadCandidate(customerId: Long, orderNo: String): CheckoutCandidate {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, customerId)
            ?: throw OrderNotFoundException()
        if (order.status != OrderStatus.PENDING_PAYMENT) {
            throw OrderStatusException("当前订单不可发起支付")
        }
        val expiresAt = requireNotNull(order.expiresAt) { "待支付订单必须设置支付截止时间" }
        if (!expiresAt.isAfter(clock.instant())) {
            throw OrderStatusException("订单已超时，无法发起支付")
        }
        return order.toCheckoutCandidate(expiresAt)
    }

    private fun bindCheckoutSession(
        orderId: Long,
        sessionId: String,
        paymentIntentId: String?,
    ): CheckoutBinding {
        if (orderRepository.attachStripeCheckoutSession(orderId, sessionId, paymentIntentId) == 1) {
            return CheckoutBinding(attached = true, sessionId = sessionId)
        }
        val current = orderRepository.findById(orderId).orElse(null)
        return CheckoutBinding(attached = false, sessionId = current?.stripeCheckoutSessionId)
    }

    private fun OrderEntity.toCheckoutCandidate(expiresAt: Instant) = CheckoutCandidate(
        orderId = requireNotNull(id),
        orderNo = orderNo,
        totalAmount = totalAmount,
        currency = currency,
        expiresAt = expiresAt,
        sessionId = stripeCheckoutSessionId,
    )

    private fun successUrl(orderNo: String): URI = URI.create(
        "${storefrontBaseUrl()}/orders/$orderNo/payment/result?session_id=%7BCHECKOUT_SESSION_ID%7D",
    )

    private fun cancelledUrl(orderNo: String): URI =
        URI.create("${storefrontBaseUrl()}/orders/$orderNo/payment/cancelled")

    private fun storefrontBaseUrl(): String = stripeProperties.checkout.storefrontBaseUrl.toString().trimEnd('/')

    private data class CheckoutCandidate(
        val orderId: Long,
        val orderNo: String,
        val totalAmount: java.math.BigDecimal,
        val currency: String,
        val expiresAt: Instant,
        val sessionId: String?,
    )

    private data class CheckoutBinding(
        val attached: Boolean,
        val sessionId: String?,
    )
}
