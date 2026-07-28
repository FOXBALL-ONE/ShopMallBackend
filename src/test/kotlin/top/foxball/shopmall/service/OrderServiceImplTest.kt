package top.foxball.shopmall.service

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.OrderServiceImpl
import top.foxball.shopmall.shared.OrderIdempotencyService
import top.foxball.shopmall.shared.OrderNoGenerator
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderServiceImplTest {
    private val orderRepository = mock(OrderRepository::class.java)
    private val orderItemRepository = mock(OrderItemRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val userService = mock(UserService::class.java)
    private val adminAccessService = mock(AdminAccessService::class.java)
    private val eventPublisher = mock(DomainEventPublisher::class.java)
    private val paymentService = mock(OrderPaymentService::class.java)
    private val idempotencyService = mock(OrderIdempotencyService::class.java)
    private val orderNoGenerator = mock(OrderNoGenerator::class.java)
    private val objectMapper = mock(ObjectMapper::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-07-25T03:00:00Z"), ZoneOffset.UTC)
    private val service = OrderServiceImpl(
        orderRepository,
        orderItemRepository,
        productRepository,
        userRepository,
        userService,
        adminAccessService,
        eventPublisher,
        paymentService,
        idempotencyService,
        orderNoGenerator,
        OrderProperties(),
        objectMapper,
        clock,
    )

    @Test
    fun `place order uses catalog prices and decrements stock by product id order`() {
        val addressId = UUID.randomUUID()
        val customer = User(id = 5, emailVerified = true)
        val address = DeliveryAddressItem(
            id = addressId,
            name = "Alex Doe",
            phone = "+14155550123",
            country = "US",
            city = "Seattle",
            address1 = "1 Market Street",
        )
        val firstProduct = product(10, "29.50")
        val secondProduct = product(20, "15.25")
        `when`(userRepository.findById(5)).thenReturn(Optional.of(customer))
        `when`(userService.getDeliveryAddress(5, addressId)).thenReturn(address)
        `when`(idempotencyService.acquire(5, "request-1"))
            .thenReturn(OrderIdempotencyService.Acquisition.Acquired)
        `when`(productRepository.findAllById(listOf(10L, 20L))).thenReturn(listOf(firstProduct, secondProduct))
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")
        `when`(orderNoGenerator.next()).thenReturn("260725030000000001ABCDEFGH")
        `when`(orderRepository.saveAndFlush(any(OrderEntity::class.java))).thenAnswer {
            it.getArgument<OrderEntity>(0).apply { id = 100 }
        }
        `when`(orderItemRepository.saveAllAndFlush(any<List<OrderItem>>())).thenAnswer {
            it.getArgument<List<OrderItem>>(0)
        }
        `when`(eventPublisher.publishInTx(anyString(), anyLong(), anyString(), anyString()))
            .thenReturn(OutboxEvent())
        `when`(productRepository.decrementStock(10, 1)).thenReturn(1)
        `when`(productRepository.decrementStock(20, 2)).thenReturn(1)

        TransactionSynchronizationManager.initSynchronization()
        try {
            val result = service.placeOrder(
                5,
                PlaceOrderCommand(
                    items = listOf(OrderLineCommand(20, 2), OrderLineCommand(10, 1)),
                    addressId = addressId,
                ),
                "request-1",
            )

            assertEquals(BigDecimal("60.00"), result.order.totalAmount)
            assertEquals(listOf(10L, 20L), result.items.map(OrderItem::productId))
            assertEquals(listOf(BigDecimal("29.50"), BigDecimal("15.25")), result.items.map(OrderItem::unitPrice))
            val stockOrder = inOrder(productRepository)
            stockOrder.verify(productRepository).decrementStock(10, 1)
            stockOrder.verify(productRepository).decrementStock(20, 2)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `customer cancellation restocks once and schedules Checkout compensation`() {
        val order = OrderEntity(
            id = 101,
            orderNo = "ORDER-101",
            customerId = 5,
            status = OrderStatus.PENDING_PAYMENT,
            stripeCheckoutSessionId = "cs_101",
            expiresAt = clock.instant().plusSeconds(1800),
        )
        val item = OrderItem(id = 201, order = order, productId = 10, quantity = 2)
        val cancelled = OrderEntity(
            id = 101,
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = OrderStatus.CANCELLED,
            stripeCheckoutSessionId = order.stripeCheckoutSessionId,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(101)).thenReturn(listOf(item))
        `when`(
            orderRepository.markCancelled(
                101,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED,
                clock.instant(),
                "customer request",
            ),
        ).thenReturn(1)
        `when`(eventPublisher.publishInTx(anyString(), anyLong(), anyString(), anyString()))
            .thenReturn(OutboxEvent())
        `when`(productRepository.restock(10, 2)).thenReturn(1)
        `when`(orderRepository.findById(101)).thenReturn(Optional.of(cancelled))

        val result = service.cancel(5, order.orderNo, "customer request")

        assertEquals(OrderStatus.CANCELLED, result.order.status)
        verify(productRepository).restock(10, 2)
        verify(paymentService).cancelOrRefund(order, "customer-cancel")
    }

    @Test
    fun `refund restores stock and sales only after paid transition succeeds`() {
        val order = OrderEntity(
            id = 100,
            orderNo = "ORDER-100",
            customerId = 5,
            status = OrderStatus.PAID,
            paymentIntentId = "pi_100",
        )
        val item = OrderItem(id = 200, order = order, productId = 10, quantity = 2)
        val cancelled = OrderEntity(
            id = 100,
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = OrderStatus.CANCELLED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(100)).thenReturn(listOf(item))
        `when`(
            orderRepository.markCancelled(
                100,
                OrderStatus.PAID,
                OrderStatus.CANCELLED,
                clock.instant(),
                "customer request",
            ),
        ).thenReturn(1)
        `when`(eventPublisher.publishInTx(anyString(), anyLong(), anyString(), anyString()))
            .thenReturn(OutboxEvent())
        `when`(productRepository.restock(10, 2)).thenReturn(1)
        `when`(productRepository.decrementSales(10, 2)).thenReturn(1)
        `when`(orderRepository.findById(100)).thenReturn(Optional.of(cancelled))

        val result = service.refund(9, order.orderNo, "customer request")

        assertEquals(OrderStatus.CANCELLED, result.order.status)
        verify(productRepository).restock(10, 2)
        verify(productRepository).decrementSales(10, 2)
        verify(paymentService).cancelOrRefund(order, "admin-refund")
    }

    private fun product(id: Long, price: String): Dress = Dress(size = Dress.Size.M).apply {
        this.id = id
        name = "Product $id"
        color = "Blue"
        this.price = BigDecimal(price)
        warehouseVolume = 10
    }
}
