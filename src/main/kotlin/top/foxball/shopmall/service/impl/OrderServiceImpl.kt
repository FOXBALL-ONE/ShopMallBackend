package top.foxball.shopmall.service.impl

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.handler.BusinessException
import top.foxball.shopmall.handler.EmailNotVerifiedException
import top.foxball.shopmall.handler.IdempotencyConflictException
import top.foxball.shopmall.handler.InsufficientStockException
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.OrderProcessingException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.OrderPageQuery
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.service.OrderPaymentView
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.OrderView
import top.foxball.shopmall.service.PlaceOrderCommand
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.OrderIdempotencyService
import top.foxball.shopmall.shared.OrderNoGenerator
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

@Service
@Transactional(readOnly = true)
class OrderServiceImpl(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val adminAccessService: AdminAccessService,
    private val eventPublisher: DomainEventPublisher,
    private val paymentService: OrderPaymentService,
    private val idempotencyService: OrderIdempotencyService,
    private val orderNoGenerator: OrderNoGenerator,
    private val orderProperties: OrderProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : OrderService {

    @Transactional
    override fun placeOrder(
        customerId: Long,
        command: PlaceOrderCommand,
        idempotencyKey: String?,
    ): OrderView {
        validatePlaceOrder(command)
        val user = userRepository.findById(customerId).orElse(null) ?: throw ResourceNotFoundException("用户不存在")
        if (!user.emailVerified) throw EmailNotVerifiedException()
        val address = userService.getDeliveryAddress(customerId, command.addressId)
            ?: throw ResourceNotFoundException("配送地址不存在")
        val normalizedLines = normalizeLines(command)
        val clientKey = idempotencyKey?.trim()?.takeIf(String::isNotEmpty) ?: requestHash(command)

        return when (val acquisition = idempotencyService.acquire(customerId, clientKey)) {
            OrderIdempotencyService.Acquisition.Acquired -> {
                try {
                    val view = createOrder(customerId, command, normalizedLines, address)
                    completeIdempotencyAfterCommit(customerId, clientKey, view.order.orderNo)
                    view
                } catch (ex: BusinessException) {
                    idempotencyService.reject(customerId, clientKey, ex.message)
                    throw ex
                } catch (ex: RuntimeException) {
                    idempotencyService.release(customerId, clientKey)
                    throw ex
                }
            }
            OrderIdempotencyService.Acquisition.Pending ->
                throw OrderProcessingException()
            is OrderIdempotencyService.Acquisition.Completed ->
                getCustomer(customerId, acquisition.orderNo)
            is OrderIdempotencyService.Acquisition.Rejected ->
                throw IdempotencyConflictException(acquisition.message)
        }
    }

    override fun listCustomer(customerId: Long, query: OrderPageQuery): Page<OrderView> {
        val pageable = pageRequest(query.page, query.size)
        return toViewPage(orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable))
    }

    override fun getCustomer(customerId: Long, orderNo: String): OrderView {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, customerId) ?: throw OrderNotFoundException()
        return view(order, includeClientSecret = true)
    }

    override fun getPayment(customerId: Long, orderNo: String): OrderPaymentView {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, customerId) ?: throw OrderNotFoundException()
        return OrderPaymentView(
            orderNo = order.orderNo,
            status = order.status,
            clientSecret = paymentService.getClientSecret(order),
            expiresAt = order.expiresAt,
        )
    }

    @Transactional
    override fun cancel(customerId: Long, orderNo: String, reason: String): OrderView {
        val normalizedReason = normalizeReason(reason)
        val order = orderRepository.lockByOrderNo(orderNo)
            ?.takeIf { it.customerId == customerId }
            ?: throw OrderNotFoundException()
        val orderId = requireNotNull(order.id)
        val items = orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(orderId)
        val changed = orderRepository.markCancelled(
            orderId,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.CANCELLED,
            clock.instant(),
            normalizedReason,
        )
        if (changed == 0) {
            if (orderRepository.findStatusById(orderId) == OrderStatus.CANCELLED) {
                return view(reload(orderId), includeClientSecret = false)
            }
            throw OrderStatusException("只有待支付订单可以取消")
        }

        publishOrderEvent(orderId, "CANCELLED")
        restock(items)
        paymentService.cancelOrRefund(order, "customer-cancel")
        return view(reload(orderId), includeClientSecret = false)
    }

    override fun listAdmin(adminId: Long, query: AdminOrderQuery): Page<OrderView> {
        adminAccessService.requireAdmin(adminId)
        val pageable = pageRequest(query.page, query.size)
        return toViewPage(
            orderRepository.findAllForAdmin(
                query.status,
                query.customerId,
                query.orderNo?.trim()?.takeIf(String::isNotEmpty),
                pageable,
            ),
        )
    }

    @Transactional
    override fun refund(adminId: Long, orderNo: String, reason: String): OrderView {
        adminAccessService.requireAdmin(adminId)
        val normalizedReason = normalizeReason(reason)
        val order = orderRepository.lockByOrderNo(orderNo) ?: throw OrderNotFoundException()
        val orderId = requireNotNull(order.id)
        val items = orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(orderId)
        val changed = orderRepository.markCancelled(
            orderId,
            OrderStatus.PAID,
            OrderStatus.CANCELLED,
            clock.instant(),
            normalizedReason,
        )
        if (changed == 0) {
            if (orderRepository.findStatusById(orderId) == OrderStatus.CANCELLED) {
                return view(reload(orderId), includeClientSecret = false)
            }
            throw OrderStatusException("只有未发货的已支付订单可以退款")
        }

        publishOrderEvent(orderId, "CANCELLED")
        restock(items)
        decrementSales(items)
        paymentService.cancelOrRefund(order, "admin-refund")
        return view(reload(orderId), includeClientSecret = false)
    }

    private fun createOrder(
        customerId: Long,
        command: PlaceOrderCommand,
        lines: List<NormalizedLine>,
        address: DeliveryAddressItem,
    ): OrderView {
        val productsById = productRepository.findAllById(lines.map(NormalizedLine::productId))
            .associateBy { requireNotNull(it.id) }
        val missingId = lines.firstOrNull { it.productId !in productsById }?.productId
        if (missingId != null) throw ResourceNotFoundException("商品不存在: $missingId")

        val orderItems = lines.map { line ->
            val product = productsById.getValue(line.productId)
            if (product.status != Product.Status.ACTIVE) {
                throw ResourceNotFoundException("商品不存在或已下架: ${line.productId}")
            }
            val unitPrice = product.price.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)
            OrderItem(
                productId = line.productId,
                productSnapshot = productSnapshot(product),
                unitPrice = unitPrice,
                quantity = line.quantity,
                lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity.toLong()))
                    .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY),
            )
        }
        val subtotal = orderItems.fold(BigDecimal.ZERO) { total, item -> total + item.lineTotal }
            .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)
        val order = orderRepository.saveAndFlush(
            OrderEntity(
                orderNo = orderNoGenerator.next(),
                customerId = customerId,
                status = OrderStatus.PENDING_PAYMENT,
                itemsSubtotal = subtotal,
                shippingFee = ZERO_MONEY,
                taxAmount = ZERO_MONEY,
                discountAmount = ZERO_MONEY,
                totalAmount = subtotal,
                currency = DEFAULT_CURRENCY,
                shippingAddress = address.toSnapshot(),
                clientMessage = command.clientMessage?.trim()?.takeIf(String::isNotEmpty),
                expiresAt = clock.instant().plusSeconds(orderProperties.paymentTimeoutMinutes * 60),
            ),
        )
        orderItems.forEach { it.order = order }
        orderItemRepository.saveAllAndFlush(orderItems)
        val orderId = requireNotNull(order.id)
        publishOrderEvent(orderId, "CREATED")
        publishOrderEvent(orderId, "PI_CREATE")

        lines.forEach { line ->
            if (productRepository.decrementStock(line.productId, line.quantity) == 0) {
                val stillActive = productRepository.findByIdAndStatus(line.productId, Product.Status.ACTIVE) != null
                if (!stillActive) throw ResourceNotFoundException("商品不存在或已下架: ${line.productId}")
                throw InsufficientStockException("商品库存不足: ${line.productId}")
            }
        }
        return OrderView(order, orderItems)
    }

    private fun normalizeLines(command: PlaceOrderCommand): List<NormalizedLine> = command.items
        .groupingBy { it.productId }
        .fold(0) { total, line -> total + line.quantity }
        .map { (productId, quantity) ->
            if (quantity > orderProperties.maxQuantityPerLine) {
                throw ParamErrorException("单个商品数量不能超过 ${orderProperties.maxQuantityPerLine}")
            }
            NormalizedLine(productId, quantity)
        }
        .sortedBy(NormalizedLine::productId)

    private fun validatePlaceOrder(command: PlaceOrderCommand) {
        if (command.items.isEmpty() || command.items.size > MAX_ORDER_LINES) {
            throw ParamErrorException("订单商品行数必须在 1 到 $MAX_ORDER_LINES 之间")
        }
        if (command.items.any { it.productId < 1 || it.quantity < 1 }) {
            throw ParamErrorException("商品编号和数量必须为正数")
        }
        if (command.clientMessage != null && command.clientMessage.length > MAX_CLIENT_MESSAGE_LENGTH) {
            throw ParamErrorException("客户留言不能超过 $MAX_CLIENT_MESSAGE_LENGTH 个字符")
        }
    }

    private fun normalizeReason(reason: String): String {
        val normalized = reason.trim()
        if (normalized.isEmpty() || normalized.length > MAX_REASON_LENGTH) {
            throw ParamErrorException("原因不能为空且不能超过 $MAX_REASON_LENGTH 个字符")
        }
        return normalized
    }

    private fun restock(items: List<OrderItem>) {
        items.sortedBy(OrderItem::productId).forEach { item ->
            check(productRepository.restock(item.productId, item.quantity) == 1) {
                "无法回补订单商品库存: ${item.productId}"
            }
        }
    }

    private fun decrementSales(items: List<OrderItem>) {
        items.sortedBy(OrderItem::productId).forEach { item ->
            check(productRepository.decrementSales(item.productId, item.quantity) == 1) {
                "无法冲销订单商品销量: ${item.productId}"
            }
        }
    }

    private fun toViewPage(orders: Page<OrderEntity>): Page<OrderView> {
        if (orders.isEmpty) return PageImpl(emptyList(), orders.pageable, orders.totalElements)
        val orderIds = orders.content.map { requireNotNull(it.id) }
        val itemsByOrderId = orderItemRepository.findAllByOrderIds(orderIds)
            .groupBy { requireNotNull(it.order?.id) }
        val views = orders.content.map { order ->
            OrderView(order, itemsByOrderId[requireNotNull(order.id)].orEmpty())
        }
        return PageImpl(views, orders.pageable, orders.totalElements)
    }

    private fun view(order: OrderEntity, includeClientSecret: Boolean): OrderView = OrderView(
        order = order,
        items = orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(requireNotNull(order.id)),
        clientSecret = if (includeClientSecret) paymentService.getClientSecret(order) else null,
    )

    private fun reload(orderId: Long): OrderEntity =
        orderRepository.findById(orderId).orElseThrow { OrderNotFoundException() }

    private fun publishOrderEvent(orderId: Long, eventType: String) {
        eventPublisher.publishInTx("ORDER", orderId, eventType, "{\"orderId\":$orderId}")
    }

    private fun completeIdempotencyAfterCommit(customerId: Long, clientKey: String, orderNo: String) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) { "下单幂等回写必须处于事务中" }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                runCatching { idempotencyService.complete(customerId, clientKey, orderNo) }
                    .onFailure { logger.error("Failed to complete idempotency key for order {}", orderNo, it) }
            }

            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    runCatching { idempotencyService.release(customerId, clientKey) }
                        .onFailure { logger.error("Failed to release idempotency key for order {}", orderNo, it) }
                }
            }
        })
    }

    private fun requestHash(command: PlaceOrderCommand): String {
        val canonical = buildString {
            command.items.sortedWith(compareBy({ it.productId }, { it.quantity })).forEach {
                append(it.productId).append(':').append(it.quantity).append(';')
            }
            append('|').append(command.addressId)
            append('|').append(command.clientMessage.orEmpty())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun productSnapshot(product: Product): String {
        val snapshot = linkedMapOf<String, Any?>(
            "productId" to product.id,
            "productType" to product::class.simpleName,
            "name" to product.name,
            "color" to product.color,
        )
        when (product) {
            is BikiniSuit -> {
                snapshot["topSize"] = product.topSize?.name
                snapshot["bottomSize"] = product.bottomSize?.name
            }
            is OnePieceSuit -> snapshot["size"] = product.size?.name
            is Dress -> snapshot["size"] = product.size?.name
            is CoverUp -> snapshot["size"] = product.size.name
        }
        return objectMapper.writeValueAsString(snapshot)
    }

    private fun DeliveryAddressItem.toSnapshot(): OrderShippingAddress = OrderShippingAddress(
        name = name,
        phone = phone,
        country = country,
        stateOrProvince = stateOrProvince,
        city = city,
        district = district,
        postalCode = postalCode,
        address1 = address1,
        address2 = address2,
        company = company,
        deliveryInstructions = deliveryInstructions,
    )

    private fun pageRequest(page: Int, size: Int): PageRequest {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) {
            throw ParamErrorException("分页参数无效")
        }
        return PageRequest.of(page, size)
    }

    private data class NormalizedLine(val productId: Long, val quantity: Int)

    private companion object {
        const val DEFAULT_CURRENCY = "USD"
        const val MONEY_SCALE = 2
        const val MAX_ORDER_LINES = 10
        const val MAX_CLIENT_MESSAGE_LENGTH = 500
        const val MAX_REASON_LENGTH = 200
        const val MAX_PAGE_SIZE = 100
        val ZERO_MONEY: BigDecimal = BigDecimal.ZERO.setScale(MONEY_SCALE)
        val logger = LoggerFactory.getLogger(OrderServiceImpl::class.java)
    }
}
