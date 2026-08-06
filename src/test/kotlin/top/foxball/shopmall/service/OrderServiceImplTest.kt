package top.foxball.shopmall.service

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.IdempotencyKeyInvalidException
import top.foxball.shopmall.handler.InsufficientStockException
import top.foxball.shopmall.handler.OrderWindowLimitException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.OrderServiceImpl
import top.foxball.shopmall.shared.OrderIdempotencyKeyService
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
import kotlin.test.assertFailsWith

class OrderServiceImplTest {
    private val orderRepository = mock(OrderRepository::class.java)
    private val orderItemRepository = mock(OrderItemRepository::class.java)
    private val shipmentItemRepository = mock(ShipmentItemRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val userService = mock(UserService::class.java)
    private val adminAccessService = mock(AdminAccessService::class.java)
    private val eventPublisher = mock(DomainEventPublisher::class.java)
    private val paymentService = mock(OrderPaymentService::class.java)
    private val idempotencyService = mock(OrderIdempotencyService::class.java)
    private val orderIdempotencyKeyService = mock(OrderIdempotencyKeyService::class.java)
    private val orderNoGenerator = mock(OrderNoGenerator::class.java)
    private val objectMapper = mock(ObjectMapper::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-07-25T03:00:00Z"), ZoneOffset.UTC)
    private val service = OrderServiceImpl(
        orderRepository,
        orderItemRepository,
        shipmentItemRepository,
        productRepository,
        userRepository,
        userService,
        adminAccessService,
        eventPublisher,
        paymentService,
        idempotencyService,
        orderIdempotencyKeyService,
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
        `when`(orderIdempotencyKeyService.isValidFor(5, "request-1")).thenReturn(true)
        `when`(orderIdempotencyKeyService.consume(5, "request-1")).thenReturn(true)
        `when`(userRepository.findByIdForUpdate(5)).thenReturn(customer)
        `when`(orderRepository.findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList(), PageRequest.of(0, 1), 0))
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
    fun `customer cancellation stores a blank reason as null`() {
        val order = OrderEntity(
            id = 102,
            orderNo = "ORDER-102",
            customerId = 5,
            status = OrderStatus.PENDING_PAYMENT,
        )
        val cancelled = OrderEntity(
            id = 102,
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = OrderStatus.CANCELLED,
            cancelReason = null,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(102)).thenReturn(emptyList())
        `when`(
            orderRepository.markCancelled(
                102,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED,
                clock.instant(),
                null,
            ),
        ).thenReturn(1)
        `when`(eventPublisher.publishInTx(anyString(), anyLong(), anyString(), anyString()))
            .thenReturn(OutboxEvent())
        `when`(orderRepository.findById(102)).thenReturn(Optional.of(cancelled))

        val result = service.cancel(5, order.orderNo, "   ")

        assertEquals(null, result.order.cancelReason)
        verify(orderRepository).markCancelled(
            102,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.CANCELLED,
            clock.instant(),
            null,
        )
    }

    @Test
    fun `customer cancellation hides a logically deleted order`() {
        val order = OrderEntity(
            id = 103,
            orderNo = "ORDER-103",
            customerId = 5,
            status = OrderStatus.DELETED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)

        assertFailsWith<OrderNotFoundException> {
            service.cancel(5, order.orderNo, null)
        }

        verify(orderRepository, never()).markCancelled(
            103,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.CANCELLED,
            clock.instant(),
            null,
        )
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

    @Test
    fun `admin deletion only marks order as deleted`() {
        val order = OrderEntity(
            id = 301,
            orderNo = "ORDER-301",
            customerId = 5,
            status = OrderStatus.COMPLETED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)

        val result = service.delete(9, order.orderNo)

        assertEquals(order.orderNo, result.orderNo)
        assertEquals(OrderStatus.DELETED, order.status)
        verify(adminAccessService).requireAdmin(9)
        verify(orderRepository, never()).delete(any(OrderEntity::class.java))
        verify(orderItemRepository, never()).deleteAllByOrderId(301)
    }

    @Test
    fun `admin deletion rejects an active order`() {
        val order = OrderEntity(
            id = 306,
            orderNo = "ORDER-306",
            customerId = 5,
            status = OrderStatus.PAID,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)

        val ex = assertFailsWith<OrderStatusException> { service.delete(9, order.orderNo) }

        assertEquals("订单需先取消或完成履约才能删除", ex.message)
        assertEquals(OrderStatus.PAID, order.status)
        verify(orderRepository, never()).delete(any(OrderEntity::class.java))
    }

    @Test
    fun `permanent deletion physically removes deleted order and items`() {
        val order = OrderEntity(
            id = 302,
            orderNo = "ORDER-302",
            customerId = 5,
            status = OrderStatus.DELETED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderRepository.countShipmentsByOrderId(302)).thenReturn(0)
        `when`(orderRepository.countSupportTicketsByOrderId(302)).thenReturn(0)

        service.permanentlyDelete(9, order.orderNo)

        val deletionOrder = inOrder(orderItemRepository, orderRepository)
        deletionOrder.verify(orderItemRepository).deleteAllByOrderId(302)
        deletionOrder.verify(orderRepository).delete(order)
        deletionOrder.verify(orderRepository).flush()
    }

    @Test
    fun `permanent deletion rejects an order that is not logically deleted`() {
        val order = OrderEntity(
            id = 305,
            orderNo = "ORDER-305",
            customerId = 5,
            status = OrderStatus.COMPLETED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)

        val ex = assertFailsWith<OrderStatusException> {
            service.permanentlyDelete(9, order.orderNo)
        }

        assertEquals("只有已逻辑删除的订单才能永久删除", ex.message)
        verify(orderItemRepository, never()).deleteAllByOrderId(305)
        verify(orderRepository, never()).delete(any(OrderEntity::class.java))
    }

    @Test
    fun `physical deletion rejects an order with shipments`() {
        val order = OrderEntity(
            id = 303,
            orderNo = "ORDER-303",
            customerId = 5,
            status = OrderStatus.DELETED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderRepository.countShipmentsByOrderId(303)).thenReturn(1)

        val ex = assertFailsWith<OrderStatusException> { service.permanentlyDelete(9, order.orderNo) }

        assertEquals("订单仍有关联运单，请先永久删除关联运单", ex.message)
        verify(orderItemRepository, never()).deleteAllByOrderId(303)
        verify(orderRepository, never()).delete(any(OrderEntity::class.java))
    }

    @Test
    fun `physical deletion rejects an order linked by a support ticket`() {
        val order = OrderEntity(
            id = 304,
            orderNo = "ORDER-304",
            customerId = 5,
            status = OrderStatus.DELETED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderRepository.countShipmentsByOrderId(304)).thenReturn(0)
        `when`(orderRepository.countSupportTicketsByOrderId(304)).thenReturn(1)

        val ex = assertFailsWith<OrderStatusException> { service.permanentlyDelete(9, order.orderNo) }

        assertEquals("订单仍有关联售后工单，不能永久删除", ex.message)
        verify(orderItemRepository, never()).deleteAllByOrderId(304)
        verify(orderRepository, never()).delete(any(OrderEntity::class.java))
    }

    @Test
    fun `place order rejects when a recent order falls inside the creation window`() {
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
        val recentOrder = OrderEntity(
            id = 200,
            orderNo = "ORDER-200",
            customerId = 5,
            status = OrderStatus.CANCELLED,
            createdAt = clock.instant().minusSeconds(60),
        )
        `when`(userRepository.findById(5)).thenReturn(Optional.of(customer))
        `when`(userService.getDeliveryAddress(5, addressId)).thenReturn(address)
        `when`(idempotencyService.acquire(5, "request-1"))
            .thenReturn(OrderIdempotencyService.Acquisition.Acquired)
        `when`(idempotencyService.replayOrderNo(anyLong(), anyString(), anyString())).thenReturn(null)
        `when`(orderIdempotencyKeyService.isValidFor(5, "request-1")).thenReturn(true)
        `when`(userRepository.findByIdForUpdate(5)).thenReturn(customer)
        `when`(orderRepository.findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1)))
            .thenReturn(PageImpl(listOf(recentOrder), PageRequest.of(0, 1), 1))

        val ex = assertFailsWith<OrderWindowLimitException> {
            service.placeOrder(
                5,
                PlaceOrderCommand(
                    items = listOf(OrderLineCommand(10, 1)),
                    addressId = addressId,
                ),
                "request-1",
            )
        }

        assertEquals(540L, ex.retryAfterSeconds)
        verify(orderIdempotencyKeyService).consume(5, "request-1")
        verify(orderRepository).findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1))
    }

    @Test
    fun `place order rejects an issued key that does not belong to the customer`() {
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
        `when`(userRepository.findById(5)).thenReturn(Optional.of(customer))
        `when`(userService.getDeliveryAddress(5, addressId)).thenReturn(address)
        `when`(idempotencyService.acquire(5, "forged-key"))
            .thenReturn(OrderIdempotencyService.Acquisition.Acquired)
        `when`(idempotencyService.replayOrderNo(anyLong(), anyString(), anyString())).thenReturn(null)
        `when`(orderIdempotencyKeyService.isValidFor(5, "forged-key")).thenReturn(false)

        assertFailsWith<IdempotencyKeyInvalidException> {
            service.placeOrder(
                5,
                PlaceOrderCommand(
                    items = listOf(OrderLineCommand(10, 1)),
                    addressId = addressId,
                ),
                "forged-key",
            )
        }

        verify(idempotencyService).reject(5, "forged-key", "幂等键无效或不属于当前用户")
        verify(orderIdempotencyKeyService).consume(5, "forged-key")
        verify(orderRepository, never()).findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1))
    }

    @Test
    fun `place order failure consumes the issued key before rethrowing`() {
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
        `when`(userRepository.findById(5)).thenReturn(Optional.of(customer))
        `when`(userService.getDeliveryAddress(5, addressId)).thenReturn(address)
        `when`(idempotencyService.acquire(5, "request-1"))
            .thenReturn(OrderIdempotencyService.Acquisition.Acquired)
        `when`(idempotencyService.replayOrderNo(anyLong(), anyString(), anyString())).thenReturn(null)
        `when`(orderIdempotencyKeyService.isValidFor(5, "request-1")).thenReturn(true)
        `when`(userRepository.findByIdForUpdate(5)).thenReturn(customer)
        `when`(orderRepository.findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList(), PageRequest.of(0, 1), 0))
        `when`(productRepository.findAllById(listOf(10L))).thenReturn(listOf(product(10, "9.99")))
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")
        `when`(orderNoGenerator.next()).thenReturn("260725030000000002ABCDEFGH")
        `when`(orderRepository.saveAndFlush(any(OrderEntity::class.java))).thenAnswer {
            it.getArgument<OrderEntity>(0).apply { id = 100 }
        }
        `when`(orderItemRepository.saveAllAndFlush(any<List<OrderItem>>())).thenAnswer {
            it.getArgument<List<OrderItem>>(0)
        }
        `when`(eventPublisher.publishInTx(anyString(), anyLong(), anyString(), anyString()))
            .thenReturn(OutboxEvent())
        `when`(productRepository.decrementStock(10, 1)).thenReturn(0)
        `when`(productRepository.findByIdAndStatus(10, Product.Status.ACTIVE))
            .thenReturn(product(10, "9.99"))

        assertFailsWith<InsufficientStockException> {
            service.placeOrder(
                5,
                PlaceOrderCommand(
                    items = listOf(OrderLineCommand(10, 1)),
                    addressId = addressId,
                ),
                "request-1",
            )
        }

        verify(idempotencyService).reject(5, "request-1", "商品库存不足: 10")
        verify(orderIdempotencyKeyService).consume(5, "request-1")
    }

    private fun product(id: Long, price: String): Dress = Dress(size = Dress.Size.M).apply {
        this.id = id
        name = "Product $id"
        color = "Blue"
        this.price = BigDecimal(price)
        warehouseVolume = 10
    }
}
