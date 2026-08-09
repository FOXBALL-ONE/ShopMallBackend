package top.foxball.shopmall.service.impl

import com.stripe.model.Event
import com.stripe.model.checkout.Session
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.StripeWebhookEventRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminOrderPaymentQuerySource
import top.foxball.shopmall.service.AdminOrderPaymentView
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.service.payMent.PaymentQueryRequest
import top.foxball.shopmall.service.payMent.stripe.StripeService
import top.foxball.shopmall.shared.PaymentIntentCoordinator
import java.time.Clock

/** 处理 Stripe Checkout 回调及订单取消后的异步支付补偿。 */
@Service
class OrderPaymentServiceImpl(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val productRepository: ProductRepository,
    private val webhookEventRepository: StripeWebhookEventRepository,
    private val eventPublisher: DomainEventPublisher,
    private val paymentIntentCoordinator: PaymentIntentCoordinator,
    private val adminAccessService: AdminAccessService,
    private val stripeService: StripeService,
    private val clock: Clock,
) : OrderPaymentService {
    /**
     * 先声明回调事件的幂等归属，再根据 Checkout Session 状态推进订单。
     * 已取消订单收到成功支付回调时，不重新激活订单，而是登记退款补偿事件。
     */
    @Transactional
    override fun handleWebhookEvent(event: Event) {
        if (webhookEventRepository.claim(event.id, event.type) == 0) return
        if (event.type !in SUPPORTED_CHECKOUT_EVENTS) return
        val eventObject = event.dataObjectDeserializer.getObject().orElse(null)
            ?: throw IllegalStateException(
                "Stripe Checkout webhook ${event.id} could not be deserialized; API version=${event.apiVersion}",
            )
        val session = eventObject as? Session
            ?: throw IllegalStateException("Stripe Checkout webhook ${event.id} did not contain a Checkout Session")
        val sessionId = requireNotNull(session.id) { "Stripe Checkout webhook did not contain a session id" }
        val order = orderRepository.findByStripeCheckoutSessionId(sessionId)
            ?: throw IllegalStateException("Checkout session $sessionId is not attached to an order")
        session.paymentIntent?.let {
            orderRepository.attachPaymentIntentToStripeCheckoutSession(sessionId, it)
        }

        when (event.type) {
            "checkout.session.completed" -> {
                if (session.paymentStatus == "paid") markPaid(order, session.paymentIntent)
            }
            "checkout.session.async_payment_succeeded" -> markPaid(order, session.paymentIntent)
            "checkout.session.async_payment_failed" -> logger.warn(
                "Stripe Checkout session {} reported asynchronous payment failure",
                sessionId,
            )
            "checkout.session.expired" -> cancelExpiredPendingOrder(order)
        }
    }

    override fun queryAdminPaymentStatus(adminId: Long, orderNo: String): AdminOrderPaymentView {
        adminAccessService.requireAdmin(adminId)
        val order = orderRepository.findByOrderNo(orderNo) ?: throw OrderNotFoundException()
        val checkoutSession = if (order.paymentIntentId == null) {
            order.stripeCheckoutSessionId?.let(stripeService::retrieveCheckoutSession)
        } else {
            null
        }
        val paymentIntentId = order.paymentIntentId ?: checkoutSession?.paymentIntentId

        if (paymentIntentId != null) {
            val payment = stripeService.queryPayment(PaymentQueryRequest(paymentIntentId))
            val amount = payment.amount
            return AdminOrderPaymentView(
                orderNo = order.orderNo,
                orderStatus = order.status,
                provider = stripeService.provider,
                providerStatus = payment.status,
                querySource = AdminOrderPaymentQuerySource.PAYMENT_INTENT,
                paymentIntentId = payment.providerPaymentId ?: paymentIntentId,
                checkoutSessionId = order.stripeCheckoutSessionId,
                paymentIntentStatus = payment.rawStatus,
                checkoutSessionStatus = checkoutSession?.status,
                checkoutPaymentStatus = checkoutSession?.paymentStatus,
                amount = amount,
                amountMatchesOrder = amount.currency == order.currency && amount.value.compareTo(order.totalAmount) == 0,
                failureCode = payment.failureCode,
                failureMessage = payment.failureMessage,
            )
        }

        if (checkoutSession != null) {
            val amount = checkoutSession.amount
            return AdminOrderPaymentView(
                orderNo = order.orderNo,
                orderStatus = order.status,
                provider = stripeService.provider,
                providerStatus = checkoutSession.collectionStatus,
                querySource = AdminOrderPaymentQuerySource.CHECKOUT_SESSION,
                paymentIntentId = null,
                checkoutSessionId = checkoutSession.id,
                paymentIntentStatus = null,
                checkoutSessionStatus = checkoutSession.status,
                checkoutPaymentStatus = checkoutSession.paymentStatus,
                amount = amount,
                amountMatchesOrder = amount?.let {
                    it.currency == order.currency && it.value.compareTo(order.totalAmount) == 0
                },
                failureCode = null,
                failureMessage = null,
            )
        }

        throw OrderStatusException("订单尚未创建 Stripe 支付记录")
    }

    /** 将远端操作登记为订单外盒事件，确保失败时可由现有外盒机制重试。 */
    override fun cancelOrRefund(order: OrderEntity, reasonKey: String) {
        val orderId = requireNotNull(order.id)
        eventPublisher.publishInTx(
            "ORDER",
            orderId,
            PAYMENT_CANCEL_OR_REFUND_EVENT,
            "{\"reasonKey\":\"$reasonKey\"}",
        )
    }

    /** 外盒消费者调用：使 Checkout 会话失效，并在已支付时创建幂等退款。 */
    override fun reconcileCancellation(orderId: Long) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        paymentIntentCoordinator.cancelOrRefund(
            checkoutSessionId = order.stripeCheckoutSessionId,
            paymentIntentId = order.paymentIntentId,
            idempotencyKey = refundIdempotencyKey(order.orderNo),
        )
    }

    /** 外盒消费者调用：处理订单已取消但 Checkout 支付随后成功的冲突退款。 */
    override fun reconcileConflictRefund(orderId: Long) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val paymentIntentId = order.paymentIntentId ?: return
        paymentIntentCoordinator.refund(paymentIntentId, refundIdempotencyKey(order.orderNo))
    }

    private fun markPaid(order: OrderEntity, paymentIntentId: String?) {
        val orderId = requireNotNull(order.id)
        if (
            orderRepository.markPaid(
                orderId,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.PAID,
                clock.instant(),
            ) == 1
        ) {
            orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(orderId).forEach {
                check(productRepository.incrementSales(it.productId, it.quantity) == 1) {
                    "Unable to increment product sales: ${it.productId}"
                }
            }
            eventPublisher.publishInTx("ORDER", orderId, "PAID", "{\"orderId\":$orderId}")
            return
        }

        when (orderRepository.findStatusById(orderId)) {
            OrderStatus.PAID -> Unit
            OrderStatus.CANCELLED -> {
                if (paymentIntentId != null) {
                    eventPublisher.publishInTx(
                        "ORDER",
                        orderId,
                        PAYMENT_CONFLICT_REFUND_EVENT,
                        "{\"orderId\":$orderId}",
                    )
                } else {
                    logger.error("Paid Checkout session for cancelled order {} did not include a PaymentIntent", order.orderNo)
                }
            }
            OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED -> logger.error(
                "Paid Checkout session conflicts with fulfilled order {}",
                order.orderNo,
            )
            else -> logger.error("Paid Checkout session could not advance order {}", order.orderNo)
        }
    }

    private fun cancelExpiredPendingOrder(order: OrderEntity) {
        val orderId = requireNotNull(order.id)
        if (
            orderRepository.markCancelled(
                orderId,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED,
                clock.instant(),
                "CHECKOUT_SESSION_EXPIRED",
            ) == 0
        ) {
            return
        }
        orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(orderId).forEach {
            check(productRepository.restock(it.productId, it.quantity) == 1) {
                "Unable to restock expired order product: ${it.productId}"
            }
        }
        eventPublisher.publishInTx("ORDER", orderId, "TIMEOUT", "{\"orderId\":$orderId}")
        eventPublisher.publishInTx("ORDER", orderId, "CANCELLED", "{\"orderId\":$orderId}")
    }

    private companion object {
        const val PAYMENT_CANCEL_OR_REFUND_EVENT = "PAYMENT_CANCEL_OR_REFUND"
        const val PAYMENT_CONFLICT_REFUND_EVENT = "PAYMENT_CONFLICT_REFUND"
        const val CANCELLED_ORDER_REFUND_SUFFIX = ":cancelled-order-refund"
        val SUPPORTED_CHECKOUT_EVENTS = setOf(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed",
            "checkout.session.expired",
        )
        val logger = LoggerFactory.getLogger(OrderPaymentServiceImpl::class.java)

        fun refundIdempotencyKey(orderNo: String): String = orderNo + CANCELLED_ORDER_REFUND_SUFFIX
    }
}
