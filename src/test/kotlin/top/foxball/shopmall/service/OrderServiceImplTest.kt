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
import tools.jackson.databind.ObjectMapper
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderPaymentStatus
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.OrderServiceImpl
import top.foxball.shopmall.shared.OrderIdempotencyKeyService
import top.foxball.shopmall.shared.OrderIdempotencyService
import top.foxball.shopmall.shared.OrderNoGenerator
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
    private val variantRepository = mock(ProductVariantRepository::class.java)
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
        variantRepository,
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
    fun `place order locks variants in stable order and stores sku snapshots in USD`() {
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
        val firstVariant = variant(10, 100, "29.50")
        val secondVariant = variant(20, 200, "15.25")
        `when`(userRepository.findById(5)).thenReturn(Optional.of(customer))
        `when`(userService.getDeliveryAddress(5, addressId)).thenReturn(address)
        `when`(idempotencyService.acquire(5, "request-1"))
            .thenReturn(OrderIdempotencyService.Acquisition.Acquired)
        `when`(orderIdempotencyKeyService.isValidFor(5, "request-1")).thenReturn(true)
        `when`(orderIdempotencyKeyService.consume(5, "request-1")).thenReturn(true)
        `when`(userRepository.findByIdForUpdate(5)).thenReturn(customer)
        `when`(orderRepository.findByCustomerIdOrderByCreatedAtDesc(5, PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList(), PageRequest.of(0, 1), 0))
        `when`(variantRepository.findAllDetailedByIdForUpdate(listOf(10L, 20L)))
            .thenReturn(listOf(firstVariant, secondVariant))
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{\"currency\":\"USD\"}")
        `when`(orderNoGenerator.next()).thenReturn("260725030000000001ABCDEFGH")
        `when`(orderRepository.saveAndFlush(any(OrderEntity::class.java))).thenAnswer {
            it.getArgument<OrderEntity>(0).apply { id = 100 }
        }
        `when`(orderItemRepository.saveAllAndFlush(any<List<OrderItem>>())).thenAnswer {
            it.getArgument<List<OrderItem>>(0)
        }
        `when`(eventPublisher.publishInTx(anyString(), anyLong(), anyString(), anyString()))
            .thenReturn(OutboxEvent())
        `when`(variantRepository.decrementStock(10, 1)).thenReturn(1)
        `when`(variantRepository.decrementStock(20, 2)).thenReturn(1)

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

            assertEquals("USD", result.order.currency)
            assertEquals(BigDecimal("60.00"), result.order.totalAmount)
            assertEquals(listOf(10L, 20L), result.items.map(OrderItem::variantId))
            assertEquals(listOf("SKU-10", "SKU-20"), result.items.map(OrderItem::sku))
            assertEquals(listOf(100L, 200L), result.items.map(OrderItem::productId))
            val stockOrder = inOrder(variantRepository)
            stockOrder.verify(variantRepository).decrementStock(10, 1)
            stockOrder.verify(variantRepository).decrementStock(20, 2)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `customer cancellation restocks by variant and schedules payment compensation`() {
        val order = OrderEntity(
            id = 101,
            orderNo = "ORDER-101",
            customerId = 5,
            status = OrderStatus.PENDING_PAYMENT,
            stripeCheckoutSessionId = "cs_101",
        )
        val item = OrderItem(
            id = 201,
            order = order,
            productId = 100,
            variantId = 10,
            sku = "SKU-10",
            quantity = 2,
        )
        val cancelled = OrderEntity(
            id = 101,
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = OrderStatus.CANCELLED,
            stripeCheckoutSessionId = order.stripeCheckoutSessionId,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(101)).thenReturn(listOf(item))
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
        `when`(variantRepository.restock(10, 2)).thenReturn(1)
        `when`(orderRepository.findById(101)).thenReturn(Optional.of(cancelled))

        val result = service.cancel(5, order.orderNo, "customer request")

        assertEquals(OrderStatus.CANCELLED, result.order.status)
        verify(variantRepository).restock(10, 2)
        verify(paymentService).cancelOrRefund(order, "customer-cancel")
    }

    @Test
    fun `customer can complete a delivered order`() {
        val order = OrderEntity(
            id = 105,
            orderNo = "ORDER-105",
            customerId = 5,
            status = OrderStatus.DELIVERED,
            paymentStatus = OrderPaymentStatus.PAID,
        )
        val completed = OrderEntity(
            id = 105,
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = OrderStatus.COMPLETED,
            paymentStatus = OrderPaymentStatus.PAID,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(
            orderRepository.transitionStatus(
                105,
                OrderStatus.DELIVERED,
                OrderStatus.COMPLETED,
            ),
        ).thenReturn(1)
        `when`(orderRepository.findById(105)).thenReturn(Optional.of(completed))

        val result = service.complete(5, order.orderNo)

        assertEquals(OrderStatus.COMPLETED, result.status)
        verify(eventPublisher).publishInTx("ORDER", 105, "COMPLETED", "{\"orderId\":105}")
    }

    @Test
    fun `customer cannot complete an order before delivery`() {
        val order = OrderEntity(
            id = 106,
            orderNo = "ORDER-106",
            customerId = 5,
            status = OrderStatus.SHIPPED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(
            orderRepository.transitionStatus(
                106,
                OrderStatus.DELIVERED,
                OrderStatus.COMPLETED,
            ),
        ).thenReturn(0)
        `when`(orderRepository.findStatusById(106)).thenReturn(OrderStatus.SHIPPED)

        assertFailsWith<OrderStatusException> {
            service.complete(5, order.orderNo)
        }

        verify(eventPublisher, never()).publishInTx("ORDER", 106, "COMPLETED", "{\"orderId\":106}")
    }

    @Test
    fun `repeating customer completion keeps an order completed without another event`() {
        val order = OrderEntity(
            id = 107,
            orderNo = "ORDER-107",
            customerId = 5,
            status = OrderStatus.COMPLETED,
            paymentStatus = OrderPaymentStatus.PAID,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)

        val result = service.complete(5, order.orderNo)

        assertEquals(OrderStatus.COMPLETED, result.status)
        verify(orderRepository, never()).transitionStatus(
            107,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
        )
        verify(eventPublisher, never()).publishInTx("ORDER", 107, "COMPLETED", "{\"orderId\":107}")
    }

    @Test
    fun `customer cannot complete another customer's order`() {
        val order = OrderEntity(
            id = 108,
            orderNo = "ORDER-108",
            customerId = 6,
            status = OrderStatus.DELIVERED,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)

        assertFailsWith<ForbiddenException> {
            service.complete(5, order.orderNo)
        }

        verify(orderRepository, never()).transitionStatus(
            108,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
        )
    }

    @Test
    fun `administrator manually marks a pending Stripe order as paid with normal payment side effects`() {
        val order = OrderEntity(
            id = 102,
            orderNo = "ORDER-102",
            customerId = 5,
            status = OrderStatus.PENDING_PAYMENT,
            paymentStatus = OrderPaymentStatus.PENDING_PAYMENT,
        )
        val item = OrderItem(
            id = 202,
            order = order,
            productId = 100,
            variantId = 10,
            sku = "SKU-10",
            quantity = 2,
        )
        val paid = OrderEntity(
            id = 102,
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = OrderStatus.PAID,
            paymentStatus = OrderPaymentStatus.PAID,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(
            orderRepository.markPaid(
                102,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.PAID,
                clock.instant(),
            ),
        ).thenReturn(1)
        `when`(orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(102)).thenReturn(listOf(item))
        `when`(variantRepository.incrementSales(10, 2)).thenReturn(1)
        `when`(orderRepository.findById(102)).thenReturn(Optional.of(paid))

        val result = service.updateAdminStatus(99, order.orderNo, OrderStatus.PAID)

        assertEquals(OrderStatus.PAID, result.status)
        assertEquals(OrderPaymentStatus.PAID, result.paymentStatus)
        verify(adminAccessService).requireAdmin(99)
        verify(variantRepository).incrementSales(10, 2)
        verify(eventPublisher).publishInTx("ORDER", 102, "PAID", "{\"orderId\":102}")
        verify(paymentService, never()).cancelOrRefund(order, "admin-manual-payment-status")
    }

    @Test
    fun `administrator manually cancels a pending Stripe order and restores stock`() {
        val order = OrderEntity(
            id = 103,
            orderNo = "ORDER-103",
            customerId = 5,
            status = OrderStatus.PENDING_PAYMENT,
            paymentStatus = OrderPaymentStatus.PENDING_PAYMENT,
            stripeCheckoutSessionId = "cs_103",
        )
        val item = OrderItem(
            id = 203,
            order = order,
            productId = 100,
            variantId = 10,
            sku = "SKU-10",
            quantity = 2,
        )
        val cancelled = OrderEntity(
            id = 103,
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = OrderStatus.CANCELLED,
            paymentStatus = OrderPaymentStatus.CANCELLED,
            stripeCheckoutSessionId = order.stripeCheckoutSessionId,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)
        `when`(orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(103)).thenReturn(listOf(item))
        `when`(
            orderRepository.markCancelled(
                103,
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CANCELLED,
                clock.instant(),
                "管理员根据 Stripe 查询结果手动取消",
            ),
        ).thenReturn(1)
        `when`(variantRepository.restock(10, 2)).thenReturn(1)
        `when`(orderRepository.findById(103)).thenReturn(Optional.of(cancelled))

        val result = service.updateAdminStatus(99, order.orderNo, OrderStatus.CANCELLED)

        assertEquals(OrderStatus.CANCELLED, result.status)
        assertEquals(OrderPaymentStatus.CANCELLED, result.paymentStatus)
        verify(variantRepository).restock(10, 2)
        verify(eventPublisher).publishInTx("ORDER", 103, "CANCELLED", "{\"orderId\":103}")
        verify(paymentService).cancelOrRefund(order, "admin-manual-payment-status")
    }

    @Test
    fun `administrator cannot overwrite a non-pending order from the Stripe status editor`() {
        val order = OrderEntity(
            id = 104,
            orderNo = "ORDER-104",
            customerId = 5,
            status = OrderStatus.PAID,
            paymentStatus = OrderPaymentStatus.PAID,
        )
        `when`(orderRepository.lockByOrderNo(order.orderNo)).thenReturn(order)

        assertFailsWith<OrderStatusException> {
            service.updateAdminStatus(99, order.orderNo, OrderStatus.CANCELLED)
        }

        verify(orderRepository, never()).markCancelled(
            104,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.CANCELLED,
            clock.instant(),
            "管理员根据 Stripe 查询结果手动取消",
        )
    }

    private fun variant(variantId: Long, productId: Long, price: String): ProductVariant {
        val product = Product(
            id = productId,
            productType = ProductType(id = 1, code = "DRESS", name = "Dress"),
            name = "Product $productId",
            status = Product.Status.ACTIVE,
        )
        return ProductVariant(
            id = variantId,
            sku = "SKU-$variantId",
            size = "M",
            color = "Blue",
            price = BigDecimal(price),
            warehouseVolume = 10,
            status = ProductVariant.Status.ACTIVE,
            optionSignature = "signature-$variantId",
        ).also(product::addVariant)
    }
}
