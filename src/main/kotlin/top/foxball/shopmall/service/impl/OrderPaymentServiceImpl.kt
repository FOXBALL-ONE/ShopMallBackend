package top.foxball.shopmall.service.impl

import com.stripe.StripeClient
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentCreateParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.StripeWebhookEventRepository
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.shared.PaymentIntentCoordinator
import java.math.RoundingMode
import java.time.Clock

@Service
class OrderPaymentServiceImpl(
    private val stripeClient: StripeClient,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val productRepository: ProductRepository,
    private val webhookEventRepository: StripeWebhookEventRepository,
    private val eventPublisher: DomainEventPublisher,
    private val paymentIntentCoordinator: PaymentIntentCoordinator,
    private val clock: Clock,
) : OrderPaymentService {
    @Transactional
    override fun createPaymentIntent(orderId: Long): String? {
        val order = orderRepository.findById(orderId).orElseThrow(::OrderNotFoundException)
        order.paymentIntentId?.let { return stripeClient.paymentIntents().retrieve(it).clientSecret }
        if (order.status != OrderStatus.PENDING_PAYMENT) return null

        val params = PaymentIntentCreateParams.builder()
            .setAmount(
                order.totalAmount.movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact(),
            )
            .setCurrency(order.currency.lowercase())
            .putMetadata("orderNo", order.orderNo)
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build(),
            )
            .build()
        val paymentIntent = stripeClient.paymentIntents().create(
            params,
            RequestOptions.builder().setIdempotencyKey("${order.orderNo}:payment-intent").build(),
        )
        val attached = orderRepository.attachPaymentIntent(orderId, paymentIntent.id)
        if (attached == 0) {
            logger.warn("Payment intent {} was not attached to order {}", paymentIntent.id, order.orderNo)
            return null
        }
        return paymentIntent.clientSecret
    }

    override fun getClientSecret(order: OrderEntity): String? =
        order.paymentIntentId?.let { stripeClient.paymentIntents().retrieve(it).clientSecret }

    @Transactional
    override fun handleWebhookEvent(event: Event) {
        if (webhookEventRepository.claim(event.id, event.type) == 0) return
        val paymentIntent = event.dataObjectDeserializer.getObject().orElse(null) as? PaymentIntent
        if (paymentIntent == null) {
            logger.warn("Stripe webhook {} did not contain a PaymentIntent", event.id)
            return
        }
        when (event.type) {
            "payment_intent.succeeded" -> handleSucceeded(paymentIntent)
            "payment_intent.payment_failed" -> logger.warn(
                "Payment intent {} failed: {}",
                paymentIntent.id,
                paymentIntent.lastPaymentError?.message,
            )
        }
    }

    override fun cancelOrRefund(order: OrderEntity, reasonKey: String) {
        val paymentIntentId = order.paymentIntentId ?: return
        val action = {
            try {
                paymentIntentCoordinator.cancelOrRefund(
                    paymentIntentId,
                    "${order.orderNo}:$reasonKey",
                )
            } catch (ex: Exception) {
                logger.error("Failed to cancel/refund payment intent {}", paymentIntentId, ex)
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = action()
            })
        } else {
            action()
        }
    }

    private fun handleSucceeded(paymentIntent: PaymentIntent) {
        val order = orderRepository.findByPaymentIntentId(paymentIntent.id)
        if (order == null) {
            logger.error("No order found for succeeded payment intent {}", paymentIntent.id)
            return
        }
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
            OrderStatus.CANCELLED -> scheduleConflictRefund(order, paymentIntent.id)
            OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED -> logger.error(
                "Succeeded payment intent {} conflicts with fulfilled order {}",
                paymentIntent.id,
                order.orderNo,
            )
            else -> logger.error(
                "Succeeded payment intent {} could not advance order {}",
                paymentIntent.id,
                order.orderNo,
            )
        }
    }

    private fun scheduleConflictRefund(order: OrderEntity, paymentIntentId: String) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                try {
                    paymentIntentCoordinator.refund(
                        paymentIntentId,
                        "${order.orderNo}:conflict-refund",
                    )
                } catch (ex: Exception) {
                    logger.error("Conflict refund failed for order {}", order.orderNo, ex)
                }
            }
        })
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OrderPaymentServiceImpl::class.java)
    }
}
