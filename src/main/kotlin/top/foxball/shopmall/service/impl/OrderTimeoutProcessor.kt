package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.OrderPaymentService
import java.time.Clock

@Service
class OrderTimeoutProcessor(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val productRepository: ProductRepository,
    private val paymentService: OrderPaymentService,
    private val eventPublisher: DomainEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun cancelExpired(orderId: Long) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val items = orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(orderId)
        val changed = orderRepository.markCancelled(
            orderId,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.CANCELLED,
            clock.instant(),
            "PAYMENT_TIMEOUT",
        )
        if (changed == 0) return

        items.forEach {
            check(productRepository.restock(it.productId, it.quantity) == 1) {
                "Unable to restock expired order product: ${it.productId}"
            }
        }
        eventPublisher.publishInTx("ORDER", orderId, "TIMEOUT", "{\"orderId\":$orderId}")
        eventPublisher.publishInTx("ORDER", orderId, "CANCELLED", "{\"orderId\":$orderId}")
        paymentService.cancelOrRefund(order, "timeout-refund")
    }
}
