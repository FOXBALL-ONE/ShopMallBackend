package top.foxball.shopmall.service.impl

import org.springframework.dao.DataIntegrityViolationException
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
import top.foxball.shopmall.handler.IdempotencyKeyInvalidException
import top.foxball.shopmall.handler.InsufficientStockException
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.OrderProcessingException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.handler.OrderWindowLimitException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminOrderDetails
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.OrderPageQuery
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.service.OrderPaymentView
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.OrderView
import top.foxball.shopmall.service.PlaceOrderCommand
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.OrderIdempotencyKeyService
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
    private val shipmentItemRepository: ShipmentItemRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val adminAccessService: AdminAccessService,
    private val eventPublisher: DomainEventPublisher,
    private val paymentService: OrderPaymentService,
    private val idempotencyService: OrderIdempotencyService,
    private val orderIdempotencyKeyService: OrderIdempotencyKeyService,
    private val orderNoGenerator: OrderNoGenerator,
    private val orderProperties: OrderProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : OrderService {

    @Transactional
    override fun placeOrder(
        customerId: Long,
        command: PlaceOrderCommand,
        idempotencyKey: String,
    ): OrderView {
        if (command.items.isEmpty() || command.items.size > MAX_ORDER_LINES) {
            throw ParamErrorException("订单商品行数必须在 1 到 $MAX_ORDER_LINES 之间")
        }
        if (command.items.any { it.productId < 1 || it.quantity < 1 }) {
            throw ParamErrorException("商品编号和数量必须为正数")
        }
        if (command.clientMessage != null && command.clientMessage.length > MAX_CLIENT_MESSAGE_LENGTH) {
            throw ParamErrorException("客户留言不能超过 $MAX_CLIENT_MESSAGE_LENGTH 个字符")
        }
        val user = userRepository.findById(customerId).orElse(null)
            ?: throw ResourceNotFoundException("用户不存在")
        if (!user.emailVerified) throw EmailNotVerifiedException()
        val address = userService.getDeliveryAddress(customerId, command.addressId)
            ?: throw ResourceNotFoundException("配送地址不存在")
        val normalizedLines = command.items
            .groupingBy { it.productId }
            .fold(0) { total, line -> total + line.quantity }
            .map { (productId, quantity) ->
                if (quantity > orderProperties.maxQuantityPerLine) {
                    throw ParamErrorException("单个商品数量不能超过 ${orderProperties.maxQuantityPerLine}")
                }
                NormalizedLine(productId, quantity)
            }
            .sortedBy(NormalizedLine::productId)
        val clientKey = idempotencyKey.trim()
        val canonicalRequest = buildString {
            command.items.sortedWith(compareBy({ it.productId }, { it.quantity })).forEach {
                append(it.productId).append(':').append(it.quantity).append(';')
            }
            append('|').append(command.addressId)
            append('|').append(command.clientMessage.orEmpty())
        }
        val dbRequestHash = MessageDigest.getInstance("SHA-256")
            .digest(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        //Acquired 分支的数据库回放会直接返回，Redis 保持 PENDING 到 TTL 结束；正确性仍由 DB 保证，但它不会立即恢复成 COMPLETED。
        //Pending 分支会消费签发键。若第二个并发请求恰好发生在第一个请求 acquire() 之后、isValidFor() 之前，第二个请求可能先删除授权键，导致第一个请求随后校验失败。这是实际并发竞态。
        //recordOrderNo() 的唯一约束异常发生在 saveAndFlush()。JPA/Hibernate 通常会把当前事务标为 rollback-only，因此 catch 后用 return@run 回放旧订单未必能可靠提交；这个恢复路径需要专门的并发测试验证。
        //Completed 分支没有再次校验 dbRequestHash，因此同一个键在 Redis TTL 内携带不同参数时，当前实现会直接返回旧订单，而不是报参数冲突。
        return when (val acquisition = idempotencyService.acquire(customerId, clientKey)) {
            OrderIdempotencyService.Acquisition.Acquired -> {
                // 关键兜底点：Redis 以为首次（key 已过期或被 flushDb），但上次下单的 DB 幂等行仍在 → 直接回放，不重复创建。
                idempotencyService.replayOrderNo(customerId, clientKey, dbRequestHash)?.let {
                    return getCustomer(customerId, it)
                }
                try {
                    // 首次创建路径才校验键归属：重放请求的键已被消费，先校验会误伤合法重放（此处键不存在 → 403）。
                    // 归属校验在 try 内，保证其 403/429 终局统一 reject + consume 签发键，不留 PENDING 泄漏（设计 §4.2 步骤 7）。
                    if (!orderIdempotencyKeyService.isValidFor(customerId, clientKey)) {
                        throw IdempotencyKeyInvalidException()
                    }
                    // 权威窗口判定（用户行锁闸门）在下单事务内、首次创建路径执行；重放路径豁免。
                    enforceCreationWindow(customerId)
                    val view = run {
                        // 锁用户行串行化同一用户的并发下单，并在锁内复检下单窗口。
                        userRepository.findByIdForUpdate(customerId)
                            ?: throw ResourceNotFoundException("用户不存在")
                        enforceCreationWindow(customerId)

                        // 一次批量查询得到生成快照所需的商品实体列表，避免按订单行逐个查询形成 N+1。
                        val productsById = productRepository.findAllById(
                            normalizedLines.map(NormalizedLine::productId)
                        ).associateBy { requireNotNull(it.id) }
                        val missingId = normalizedLines.firstOrNull { it.productId !in productsById }?.productId
                        if (missingId != null) throw ResourceNotFoundException("商品不存在: $missingId")

                        // 使用服务端商品价格和展示数据构造订单快照；后续商品改价或修改展示信息不会影响历史订单。
                        val orderItems = normalizedLines.map { line ->
                            val product = productsById.getValue(line.productId)
                            if (product.status != Product.Status.ACTIVE) {
                                throw ResourceNotFoundException("商品不存在或已下架: ${line.productId}")
                            }
                            val unitPrice = product.price.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)
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
                            OrderItem(
                                productId = line.productId,
                                productSnapshot = objectMapper.writeValueAsString(snapshot),
                                unitPrice = unitPrice,
                                quantity = line.quantity,
                                lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.quantity.toLong()))
                                    .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY),
                            )
                        }
                        // 当前运费、税费和优惠均为零，因此商品小计就是订单应付总额。
                        val subtotal = orderItems.fold(BigDecimal.ZERO) { total, item -> total + item.lineTotal }
                            .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)

                        // saveAndFlush 立即取得订单主键并尽早暴露订单号、金额精度等数据库约束错误。
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
                                shippingAddress = OrderShippingAddress(
                                    name = address.name,
                                    phone = address.phone,
                                    country = address.country,
                                    stateOrProvince = address.stateOrProvince,
                                    city = address.city,
                                    district = address.district,
                                    postalCode = address.postalCode,
                                    address1 = address.address1,
                                    address2 = address.address2,
                                    company = address.company,
                                    deliveryInstructions = address.deliveryInstructions,
                                ),
                                clientMessage = command.clientMessage?.trim()?.takeIf(String::isNotEmpty),
                                expiresAt = clock.instant().plusSeconds(orderProperties.paymentTimeoutMinutes * 60),
                            ),
                        )
                        // OrderItem 持有订单外键，必须先保存订单主表并取得主键，再批量保存订单明细。
                        orderItems.forEach { it.order = order }
                        orderItemRepository.saveAllAndFlush(orderItems)
                        val orderId = requireNotNull(order.id)

                        // 外盒事件与订单处于同一事务，只有订单成功提交后才允许异步投递 CREATED。
                        publishOrderEvent(orderId, "CREATED")
                        // DB 幂等行与订单同事务写入，保证 afterCommit Redis complete 失败时仍有 DB 兜底。
                        try {
                            idempotencyService.recordOrderNo(customerId, clientKey, dbRequestHash, order.orderNo)
                        } catch (ex: DataIntegrityViolationException) {
                            val priorOrderNo = idempotencyService.replayOrderNo(customerId, clientKey, dbRequestHash)
                            if (priorOrderNo != null) {
                                return@run getCustomer(customerId, priorOrderNo)
                            }
                            throw ex
                        }

                        // 条件更新是库存并发控制的最终门闩；任一行失败都会使整个下单事务回滚。
                        normalizedLines.forEach { line ->
                            if (productRepository.decrementStock(line.productId, line.quantity) == 0) {
                                val stillActive = productRepository.findByIdAndStatus(
                                    line.productId,
                                    Product.Status.ACTIVE,
                                ) != null
                                if (!stillActive) throw ResourceNotFoundException("商品不存在或已下架: ${line.productId}")
                                throw InsufficientStockException("商品库存不足: ${line.productId}")
                            }
                        }
                        OrderView(order, orderItems)
                    }
                    check(TransactionSynchronizationManager.isSynchronizationActive()) { "下单幂等回写必须处于事务中" }
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            runCatching { idempotencyService.complete(customerId, clientKey, view.order.orderNo) }
                                .onFailure {
                                    logger.error(
                                        "Failed to complete idempotency key for order {}",
                                        view.order.orderNo,
                                        it,
                                    )
                                }
                        }

                        override fun afterCompletion(status: Int) {
                            if (status != TransactionSynchronization.STATUS_COMMITTED) {
                                runCatching { idempotencyService.release(customerId, clientKey) }
                                    .onFailure {
                                        logger.error(
                                            "Failed to release idempotency key for order {}",
                                            view.order.orderNo,
                                            it,
                                        )
                                    }
                            }
                        }
                    })
                    consumeIssuedKey(customerId, clientKey)
                    view
                } catch (ex: BusinessException) {
                    idempotencyService.reject(customerId, clientKey, ex.message)
                    consumeIssuedKey(customerId, clientKey)
                    throw ex
                } catch (ex: RuntimeException) {
                    idempotencyService.release(customerId, clientKey)
                    consumeIssuedKey(customerId, clientKey)
                    throw ex
                }
            }
            OrderIdempotencyService.Acquisition.Pending -> {
                // Redis 标记处理中。先查 DB 兜底：上次 afterCommit Redis complete 失败但 DB 已写时，这里可直接回放返回，改善体验。
                idempotencyService.replayOrderNo(customerId, clientKey, dbRequestHash)?.let {
                    return getCustomer(customerId, it)
                }
                // 同键并发请求已被首次路径串行消费（行锁）；此处键已删除，但仍是终局，统一消费保证前端重申请。
                consumeIssuedKey(customerId, clientKey)
                throw OrderProcessingException()
            }
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
        return view(order)
    }

    override fun getPayment(customerId: Long, orderNo: String): OrderPaymentView {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, customerId)
            ?: throw OrderNotFoundException()
        return OrderPaymentView(
            orderNo = order.orderNo,
            status = order.status,
            checkoutSessionId = order.stripeCheckoutSessionId,
            expiresAt = order.expiresAt,
        )
    }

    @Transactional
    override fun cancel(customerId: Long, orderNo: String, reason: String?): OrderView {
        val normalizedReason = normalizeReason(reason, required = false)
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
                return view(reload(orderId))
            }
            throw OrderStatusException("只有待支付订单可以取消")
        }

        publishOrderEvent(orderId, "CANCELLED")
        restock(items)
        paymentService.cancelOrRefund(order, "customer-cancel")
        return view(reload(orderId))
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

    override fun getAdmin(adminId: Long, orderNo: String): AdminOrderDetails {
        adminAccessService.requireAdmin(adminId)
        val order = orderRepository.findByOrderNo(orderNo) ?: throw OrderNotFoundException()
        val items = orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(requireNotNull(order.id))
        val itemIds = items.map { requireNotNull(it.id) }
        val allocatedQuantities = if (itemIds.isEmpty()) {
            emptyMap()
        } else {
            shipmentItemRepository.findActiveAllocations(itemIds).associate {
                it.orderItemId to it.allocatedQuantity.toInt()
            }
        }
        return AdminOrderDetails(order, items, allocatedQuantities)
    }

    @Transactional
    override fun refund(adminId: Long, orderNo: String, reason: String): OrderView {
        adminAccessService.requireAdmin(adminId)
        val normalizedReason = requireNotNull(normalizeReason(reason, required = true))
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
                return view(reload(orderId))
            }
            throw OrderStatusException("只有未发货的已支付订单可以退款")
        }

        publishOrderEvent(orderId, "CANCELLED")
        restock(items)
        items.sortedBy(OrderItem::productId).forEach { item ->
            check(productRepository.decrementSales(item.productId, item.quantity) == 1) {
                "无法冲销订单商品销量: ${item.productId}"
            }
        }
        paymentService.cancelOrRefund(order, "admin-refund")
        return view(reload(orderId))
    }

    private fun consumeIssuedKey(customerId: Long, clientKey: String) {
        runCatching { orderIdempotencyKeyService.consume(customerId, clientKey) }
            .onFailure { logger.warn("Failed to consume issued idempotency key for user {}", customerId, it) }
    }

    private fun enforceCreationWindow(customerId: Long) {
        val latest = orderRepository.findByCustomerIdOrderByCreatedAtDesc(
            customerId,
            PageRequest.of(0, 1),
        ).content.firstOrNull() ?: return
        val latestCreatedAt = requireNotNull(latest.createdAt)
        val elapsedSeconds = java.time.Duration.between(latestCreatedAt, clock.instant()).seconds
        val windowSeconds = orderProperties.creationWindowMinutes * 60
        if (elapsedSeconds < windowSeconds) {
            throw OrderWindowLimitException(
                retryAfterSeconds = (windowSeconds - elapsedSeconds).coerceAtLeast(1),
                message = "距上次下单不足 ${orderProperties.creationWindowMinutes} 分钟，请稍后再试",
            )
        }
    }

    private fun normalizeReason(reason: String?, required: Boolean): String? {
        val normalized = reason?.trim()?.takeIf(String::isNotEmpty)
        if (required && normalized == null) {
            throw ParamErrorException("原因不能为空")
        }
        if (normalized != null && normalized.length > MAX_REASON_LENGTH) {
            throw ParamErrorException("原因不能超过 $MAX_REASON_LENGTH 个字符")
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

    private fun view(order: OrderEntity): OrderView = OrderView(
        order = order,
        items = orderItemRepository.findAllByOrder_IdOrderByProductIdAsc(requireNotNull(order.id)),
    )

    private fun reload(orderId: Long): OrderEntity =
        orderRepository.findById(orderId).orElseThrow { OrderNotFoundException() }

    private fun publishOrderEvent(orderId: Long, eventType: String) {
        eventPublisher.publishInTx("ORDER", orderId, eventType, "{\"orderId\":$orderId}")
    }

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
