package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.IdempotencyKeyInvalidException
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.repository.OrderIdempotencyRepository
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
    private val orderIdempotencyRepository: OrderIdempotencyRepository,
    private val stripeService: StripeService,
    private val stripeProperties: StripeProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : OrderCheckoutService {
    private val transactions = TransactionTemplate(transactionManager)

    override fun openCheckout(customerId: Long, orderNo: String, idempotencyKey: String): OrderCheckoutView {
        // 支付授权以 DB 幂等行为准：订单下单时写入的键绑定必须与本次携带的键一致，防止订单号泄露后
        // 被他人发起支付会话。绑定校验依据是 DB 行而非 Redis 键 TTL，用户持有的键过期不影响支付。
        val keyBinding = orderIdempotencyRepository.findByCustomerIdAndOrderNo(customerId, orderNo)
            ?: throw IdempotencyKeyInvalidException()
        if (keyBinding.idempotencyKey != idempotencyKey.trim()) {
            throw IdempotencyKeyInvalidException()
        }

        val candidate = requireNotNull(transactions.execute {
            val order = orderRepository.findByOrderNoAndCustomerId(orderNo, customerId)
                ?: orderRepository.findByOrderNo(orderNo)
                    ?.takeIf { it.status != OrderStatus.DELETED }
                    ?.also {
                        if (it.customerId != customerId) {
                            throw ForbiddenException("只能访问自己的订单")
                        }
                    }
                ?: throw OrderNotFoundException()
            if (order.status != OrderStatus.PENDING_PAYMENT) {
                throw OrderStatusException("当前订单不可发起支付")
            }
            val expiresAt = requireNotNull(order.expiresAt) { "待支付订单必须设置支付截止时间" }
            if (!expiresAt.isAfter(clock.instant())) {
                throw OrderStatusException("订单已超时，无法发起支付")
            }
            CheckoutCandidate(
                orderId = requireNotNull(order.id),
                orderNo = order.orderNo,
                totalAmount = order.totalAmount,
                currency = order.currency,
                expiresAt = expiresAt,
                sessionId = order.stripeCheckoutSessionId,
            )
        })
        candidate.sessionId?.let { return reuseCheckout(candidate, it) }

        val storefrontBaseUrl = stripeProperties.checkout.storefrontBaseUrl.toString().trimEnd('/')
        val transaction = stripeService.createPayment(
            PaymentCreateRequest(
                merchantPaymentId = candidate.orderNo,
                amount = PaymentAmount(candidate.totalAmount, candidate.currency),
                idempotencyKey = "${candidate.orderNo}:checkout-session",
                description = "Order ${candidate.orderNo}",
                returnUrl = URI.create(
                    "$storefrontBaseUrl/orders/${candidate.orderNo}/payment/result?session_id=%7BCHECKOUT_SESSION_ID%7D",
                ),
                cancelUrl = URI.create("$storefrontBaseUrl/orders/${candidate.orderNo}/payment/cancelled"),
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
            if (orderRepository.attachStripeCheckoutSession(
                    candidate.orderId,
                    sessionId,
                    transaction.providerPaymentId,
                ) == 1
            ) {
                CheckoutBinding(attached = true, sessionId = sessionId)
            } else {
                val current = orderRepository.findById(candidate.orderId).orElse(null)
                CheckoutBinding(attached = false, sessionId = current?.stripeCheckoutSessionId)
            }
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
