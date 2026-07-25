package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.impl.OrderTimeoutProcessor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test

class OrderTimeoutProcessorTest {
    private val orderRepository = mock(OrderRepository::class.java)
    private val itemRepository = mock(OrderItemRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val paymentService = mock(OrderPaymentService::class.java)
    private val eventPublisher = mock(DomainEventPublisher::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), ZoneOffset.UTC)
    private val processor = OrderTimeoutProcessor(
        orderRepository,
        itemRepository,
        productRepository,
        paymentService,
        eventPublisher,
        clock,
    )

    @Test
    fun `winner of timeout transition restocks and schedules payment compensation`() {
        val order = OrderEntity(id = 10, orderNo = "ORDER-10", paymentIntentId = "pi_10")
        val item = OrderItem(productId = 3, quantity = 2)
        `when`(orderRepository.findById(10)).thenReturn(Optional.of(order))
        `when`(itemRepository.findAllByOrder_IdOrderByProductIdAsc(10)).thenReturn(listOf(item))
        `when`(
            orderRepository.markCancelled(
                10,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED,
                clock.instant(),
                "PAYMENT_TIMEOUT",
            ),
        ).thenReturn(1)
        `when`(productRepository.restock(3, 2)).thenReturn(1)

        processor.cancelExpired(10)

        verify(productRepository).restock(3, 2)
        verify(paymentService).cancelOrRefund(order, "timeout-refund")
        verify(eventPublisher).publishInTx("ORDER", 10, "TIMEOUT", "{\"orderId\":10}")
        verify(eventPublisher).publishInTx("ORDER", 10, "CANCELLED", "{\"orderId\":10}")
    }

    @Test
    fun `loser of timeout transition does not restock twice`() {
        val order = OrderEntity(id = 11, orderNo = "ORDER-11", paymentIntentId = "pi_11")
        `when`(orderRepository.findById(11)).thenReturn(Optional.of(order))
        `when`(itemRepository.findAllByOrder_IdOrderByProductIdAsc(11)).thenReturn(
            listOf(OrderItem(productId = 4, quantity = 1)),
        )
        `when`(
            orderRepository.markCancelled(
                11,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED,
                clock.instant(),
                "PAYMENT_TIMEOUT",
            ),
        ).thenReturn(0)

        processor.cancelExpired(11)

        verify(productRepository, never()).restock(4, 1)
        verify(paymentService, never()).cancelOrRefund(order, "timeout-refund")
    }
}
