package top.foxball.shopmall.service.impl

import com.stripe.model.Event
import com.stripe.model.Refund
import com.stripe.model.checkout.Session
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderPaymentStatus
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.ForbiddenException
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
import top.foxball.shopmall.service.OrderRefundStatusView
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentQueryRequest
import top.foxball.shopmall.service.payMent.PaymentRefundQueryRequest
import top.foxball.shopmall.service.payMent.PaymentRefundStatus
import top.foxball.shopmall.service.payMent.stripe.StripeService
import top.foxball.shopmall.shared.PaymentIntentCoordinator
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime

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
    /** 先声明回调事件的幂等归属，再推进本地付款与订单状态。 */
    @Transactional
    override fun handleWebhookEvent(event: Event) {
        if (webhookEventRepository.claim(event.id, event.type) == 0) return
        if (event.type !in SUPPORTED_EVENTS) return
        val eventObject = event.dataObjectDeserializer.getObject().orElse(null)
            ?: throw IllegalStateException(
                "Stripe webhook ${event.id} could not be deserialized; API version=${event.apiVersion}",
            )
        when (eventObject) {
            is Session -> handleCheckoutEvent(event, eventObject)
            is Refund -> handleRefundEvent(event, eventObject)
            else -> throw IllegalStateException("Stripe webhook ${event.id} contained an unsupported object")
        }
    }

    private fun handleCheckoutEvent(event: Event, session: Session) {
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

    private fun handleRefundEvent(event: Event, refund: Refund) {
        val paymentIntentId = requireNotNull(refund.paymentIntent) {
            "Stripe refund webhook ${event.id} did not contain a PaymentIntent"
        }
        val refundId = requireNotNull(refund.id) { "Stripe refund webhook ${event.id} did not contain a refund id" }
        val order = orderRepository.findByPaymentIntentId(paymentIntentId)
            ?: throw IllegalStateException("Stripe refund $refundId is not attached to an order")
        val orderId = requireNotNull(order.id)
        val cancelledOrderCompensation = order.status == OrderStatus.CANCELLED &&
            order.paymentStatus == OrderPaymentStatus.CANCELLED
        if (!order.isRefunding() && !cancelledOrderCompensation) {
            logger.info("Ignoring Stripe refund {} for order {} in {}/{}", refundId, order.orderNo, order.status, order.paymentStatus)
            return
        }
        if (order.stripeRefundId != null && order.stripeRefundId != refundId) {
            logger.warn("Ignoring Stripe refund {} because order {} is tracking refund {}", refundId, order.orderNo, order.stripeRefundId)
            return
        }
        if (cancelledOrderCompensation) {
            orderRepository.markCancelledOrderRefunding(
                orderId,
                OrderStatus.CANCELLED,
                OrderPaymentStatus.CANCELLED,
                OrderPaymentStatus.REFUNDING,
                LocalDateTime.now(clock),
            )
        }
        val expectedStatus = if (cancelledOrderCompensation) OrderStatus.CANCELLED else order.status
        if (orderRepository.recordStripeRefund(orderId, expectedStatus, OrderPaymentStatus.REFUNDING, refundId) == 0) {
            return
        }
        when (refund.status) {
            "succeeded" -> {
                if (refund.amountMatchesOrder(order)) completeRefund(order, refundId)
                else markPartiallyRefunded(order, refundId)
            }
            "failed", "canceled" -> revertRefunding(order)
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
                paymentStatus = order.paymentStatus,
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
                paymentStatus = order.paymentStatus,
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

    @Transactional
    override fun queryAdminRefundStatus(adminId: Long, orderNo: String): OrderRefundStatusView {
        adminAccessService.requireAdmin(adminId)
        val order = orderRepository.findByOrderNo(orderNo) ?: throw OrderNotFoundException()
        return queryRefundStatus(order)
    }

    @Transactional
    override fun queryCustomerRefundStatus(customerId: Long, orderNo: String): OrderRefundStatusView {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, customerId)
            ?: orderRepository.findByOrderNo(orderNo)
                ?.takeIf { it.status != OrderStatus.DELETED }
                ?.also {
                    if (it.customerId != customerId) throw ForbiddenException("只能查询自己的订单退款")
                }
            ?: throw OrderNotFoundException()
        return queryRefundStatus(order)
    }

    /** 将已在本地进入退款中的订单写入外盒，提交后再调用 Stripe。 */
    override fun requestRefund(order: OrderEntity) {
        val orderId = requireNotNull(order.id)
        eventPublisher.publishInTx(
            "ORDER",
            orderId,
            PAYMENT_REFUND_REQUESTED_EVENT,
            "{\"orderId\":$orderId}",
        )
    }

    override fun reconcileRequestedRefund(orderId: Long) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        if (order.status != OrderStatus.REFUNDING || order.paymentStatus != OrderPaymentStatus.REFUNDING) return
        val paymentIntentId = order.paymentIntentId
            ?: throw IllegalStateException("Refunding order ${order.orderNo} has no Stripe PaymentIntent")
        val refund = paymentIntentCoordinator.refund(paymentIntentId, requestedRefundIdempotencyKey(order))
        val refundId = requireNotNull(refund.id) { "Stripe refund did not contain an id" }
        orderRepository.recordStripeRefund(orderId, OrderStatus.REFUNDING, OrderPaymentStatus.REFUNDING, refundId)
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
        val refund = paymentIntentCoordinator.cancelOrRefund(
            checkoutSessionId = order.stripeCheckoutSessionId,
            paymentIntentId = order.paymentIntentId,
            idempotencyKey = refundIdempotencyKey(order.orderNo),
        ) ?: return
        val refundId = requireNotNull(refund.id) { "Stripe refund did not contain an id" }
        orderRepository.markCancelledOrderRefunding(
            orderId,
            OrderStatus.CANCELLED,
            OrderPaymentStatus.CANCELLED,
            OrderPaymentStatus.REFUNDING,
            LocalDateTime.now(clock),
        )
        orderRepository.recordStripeRefund(orderId, OrderStatus.CANCELLED, OrderPaymentStatus.REFUNDING, refundId)
    }

    /** 外盒消费者调用：处理订单已取消但 Checkout 支付随后成功的冲突退款。 */
    override fun reconcileConflictRefund(orderId: Long) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        if (order.status != OrderStatus.CANCELLED || order.paymentStatus != OrderPaymentStatus.REFUNDING) return
        val paymentIntentId = order.paymentIntentId ?: return
        val refund = paymentIntentCoordinator.refund(paymentIntentId, refundIdempotencyKey(order.orderNo))
        val refundId = requireNotNull(refund.id) { "Stripe refund did not contain an id" }
        orderRepository.recordStripeRefund(orderId, OrderStatus.CANCELLED, OrderPaymentStatus.REFUNDING, refundId)
    }

    private fun queryRefundStatus(order: OrderEntity): OrderRefundStatusView {
        if (order.paymentStatus !in setOf(
                OrderPaymentStatus.REFUNDING,
                OrderPaymentStatus.PARTIALLY_REFUNDED,
                OrderPaymentStatus.REFUNDED,
            )
        ) {
            throw OrderStatusException("当前订单没有可查询的退款")
        }
        val refundId = order.stripeRefundId
        if (refundId == null) {
            return OrderRefundStatusView(
                orderNo = order.orderNo,
                orderStatus = order.status,
                paymentStatus = order.paymentStatus,
                stripeRefundId = null,
                providerRefundStatus = null,
                refundAmount = null,
                amountMatchesOrder = null,
            )
        }
        val paymentIntentId = order.paymentIntentId
            ?: throw IllegalStateException("Refunding order ${order.orderNo} has no Stripe PaymentIntent")
        val refund = stripeService.queryRefund(PaymentRefundQueryRequest(paymentIntentId, providerRefundId = refundId))
        when (refund.status) {
            PaymentRefundStatus.SUCCEEDED -> {
                if (refund.amountMatchesOrder(order)) {
                    completeRefund(order, refund.providerRefundId)
                } else {
                    markPartiallyRefunded(order, refund.providerRefundId)
                }
            }
            PaymentRefundStatus.FAILED, PaymentRefundStatus.CANCELLED -> revertRefunding(order)
            else -> Unit
        }
        val latest = orderRepository.findById(requireNotNull(order.id)).orElse(order)
        return OrderRefundStatusView(
            orderNo = latest.orderNo,
            orderStatus = latest.status,
            paymentStatus = latest.paymentStatus,
            stripeRefundId = refund.providerRefundId,
            providerRefundStatus = refund.status.name,
            refundAmount = refund.amount,
            amountMatchesOrder = refund.amount.currency == latest.currency &&
                refund.amount.value.compareTo(latest.totalAmount) == 0,
        )
    }

    /** 仅由 Stripe 退款成功回调或主动查询调用，条件更新保证副作用最多执行一次。 */
    private fun completeRefund(order: OrderEntity, refundId: String) {
        val orderId = requireNotNull(order.id)
        if (order.status == OrderStatus.CANCELLED) {
            orderRepository.markRefunded(
                orderId,
                OrderStatus.CANCELLED,
                OrderPaymentStatus.REFUNDING,
                OrderStatus.CANCELLED,
                OrderPaymentStatus.REFUNDED,
                refundId,
                LocalDateTime.now(clock),
            )
            return
        }
        if (orderRepository.markRefunded(
                orderId,
                OrderStatus.REFUNDING,
                OrderPaymentStatus.REFUNDING,
                OrderStatus.REFUNDED,
                OrderPaymentStatus.REFUNDED,
                refundId,
                LocalDateTime.now(clock),
            ) == 0
        ) return
        orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(orderId).forEach {
            check(productRepository.restock(it.productId, it.quantity) == 1) {
                "Unable to restock refunded order product: ${it.productId}"
            }
            check(productRepository.decrementSales(it.productId, it.quantity) == 1) {
                "Unable to decrement refunded order product sales: ${it.productId}"
            }
        }
        eventPublisher.publishInTx("ORDER", orderId, "REFUNDED", "{\"orderId\":$orderId}")
    }

    private fun markPartiallyRefunded(order: OrderEntity, refundId: String) {
        val nextStatus = if (order.status == OrderStatus.CANCELLED) OrderStatus.CANCELLED else OrderStatus.PAID
        orderRepository.markPartiallyRefunded(
            requireNotNull(order.id),
            order.status,
            OrderPaymentStatus.REFUNDING,
            nextStatus,
            OrderPaymentStatus.PARTIALLY_REFUNDED,
            refundId,
            LocalDateTime.now(clock),
        )
    }

    private fun revertRefunding(order: OrderEntity) {
        val nextStatus = if (order.status == OrderStatus.CANCELLED) OrderStatus.CANCELLED else OrderStatus.PAID
        orderRepository.revertRefunding(
            requireNotNull(order.id),
            order.status,
            OrderPaymentStatus.REFUNDING,
            nextStatus,
            OrderPaymentStatus.PAID,
        )
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
                if (paymentIntentId != null && orderRepository.markCancelledOrderRefunding(
                        orderId,
                        OrderStatus.CANCELLED,
                        OrderPaymentStatus.CANCELLED,
                        OrderPaymentStatus.REFUNDING,
                        LocalDateTime.now(clock),
                    ) == 1
                ) {
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
        const val PAYMENT_REFUND_REQUESTED_EVENT = "PAYMENT_REFUND_REQUESTED"
        const val CANCELLED_ORDER_REFUND_SUFFIX = ":cancelled-order-refund"
        const val REQUESTED_REFUND_SUFFIX = ":requested-refund"
        val SUPPORTED_EVENTS = setOf(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed",
            "checkout.session.expired",
            "refund.created",
            "refund.failed",
            "refund.updated",
        )
        val logger = LoggerFactory.getLogger(OrderPaymentServiceImpl::class.java)

        fun refundIdempotencyKey(orderNo: String): String = orderNo + CANCELLED_ORDER_REFUND_SUFFIX

        fun requestedRefundIdempotencyKey(order: OrderEntity): String =
            order.orderNo + REQUESTED_REFUND_SUFFIX + ":" + requireNotNull(order.refundRequestedAt)

    }

    private fun OrderEntity.isRefunding(): Boolean =
        paymentStatus == OrderPaymentStatus.REFUNDING && status in setOf(OrderStatus.REFUNDING, OrderStatus.CANCELLED)

    private fun Refund.amountMatchesOrder(order: OrderEntity): Boolean {
        val amount = amount ?: return false
        val currency = currency?.uppercase() ?: return false
        return PaymentAmount(BigDecimal.valueOf(amount).movePointLeft(2), currency).amountMatchesOrder(order)
    }

    private fun PaymentAmount.amountMatchesOrder(order: OrderEntity): Boolean =
        currency == order.currency && value.compareTo(order.totalAmount) == 0

    private fun top.foxball.shopmall.service.payMent.PaymentRefund.amountMatchesOrder(order: OrderEntity): Boolean =
        amount.amountMatchesOrder(order)
}
