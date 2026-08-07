package top.foxball.shopmall.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.AllocationStatus
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.Status as UserStatus
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicket
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.SupportTicketMessageRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.repository.UserRepository
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 本地演示数据初始化器。
 *
 * 仅在 `shopmall.mock-data.enabled=true` 时装配，并在应用完全启动后一次性写入关联完整的
 * 用户、商品、订单、运单和客服工单。首个模拟客户作为数据集哨兵，重复启动不会重复写入。
 */
@Component
@ConditionalOnProperty(
    prefix = "shopmall.mock-data",
    name = ["enabled"],
    havingValue = "true",
)
class MockDataInitializer(
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val shipmentItemRepository: ShipmentItemRepository,
    private val supportTicketRepository: SupportTicketRepository,
    private val supportTicketMessageRepository: SupportTicketMessageRepository,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    @Value($$"${shopmall.mock-data.password:MockData123!}")
    private val mockPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun initializeMockData() {
        val now = clock.instant()
        val encodedPassword = requireNotNull(passwordEncoder.encode(mockPassword)) { "模拟账号密码编码失败" }
        val admins = seedAdmins(now, encodedPassword)
        if (userRepository.findByUsername(SENTINEL_USERNAME) != null) {
            log.info("Mock business data already exists; two test administrators have been synchronized")
            return
        }

        val customers = seedCustomers(now, encodedPassword)
        val products = seedProducts(now)
        val orders = seedOrders(customers, products, now)
        val shipmentCount = seedShipments(orders, requireNotNull(admins.first().id))
        val (ticketCount, messageCount) = seedSupportTickets(customers, admins.first(), orders, now)

        log.info(
            "Mock data initialized: users={}, products={}, orders={}, shipments={}, tickets={}, messages={}",
            customers.size + admins.size,
            products.size,
            orders.size,
            shipmentCount,
            ticketCount,
            messageCount,
        )
    }

    /** 每次启动都幂等同步两个可登录管理员，保证旧模拟数据集也能自动补齐新增账号。 */
    private fun seedAdmins(now: Instant, encodedPassword: String): List<User> {
        val existingByUsername = ADMIN_DEFINITIONS.associate { definition ->
            definition.username to userRepository.findByUsername(definition.username)
        }
        val admins = ADMIN_DEFINITIONS.map { definition ->
            val admin = existingByUsername.getValue(definition.username) ?: User(username = definition.username)
            admin.email = definition.email
            admin.password = encodedPassword
            admin.firstName = definition.firstName
            admin.lastName = definition.lastName
            admin.locale = "en-US"
            admin.currency = "USD"
            admin.emailVerified = true
            admin.role = Role.ADMIN
            admin.enabled = true
            admin.status = UserStatus.ACTIVE
            admin
        }
        val saved = userRepository.saveAllAndFlush(admins)
        val createdUsernames = existingByUsername.filterValues { it == null }.keys
        val createdAdmins = saved.filter { it.username in createdUsernames }
        if (createdAdmins.isNotEmpty()) {
            backdateCreatedAndUpdated(
                "UPDATE users SET created_at = ?, updated_at = ? WHERE id = ?",
                createdAdmins.mapIndexed { index, admin ->
                    val createdAt = historicalInstant(now, REPORT_HISTORY_DAYS.toLong() + 10 + index)
                    TimestampRow(requireNotNull(admin.id), createdAt, createdAt.plusSeconds(3_600))
                },
            )
        }
        return ADMIN_DEFINITIONS.map { definition -> saved.single { it.username == definition.username } }
    }

    /** 创建分布在最近 180 天内的模拟客户。 */
    private fun seedCustomers(now: Instant, encodedPassword: String): List<User> {
        val customers = (1..CUSTOMER_COUNT).map { number ->
            val profile = customerProfile(number)
            User(
                username = "mock_customer_%03d".format(number),
                email = "mock.customer.%03d@shopmall.local".format(number),
                password = encodedPassword,
                firstName = profile.firstName,
                lastName = profile.lastName,
                phone = profile.phone,
                locale = profile.locale,
                currency = profile.currency,
                emailVerified = true,
                marketingConsent = number % 3 == 0,
                enabled = number != CUSTOMER_COUNT,
                status = if (number == CUSTOMER_COUNT) UserStatus.INACTIVE else UserStatus.ACTIVE,
                lastLoginAt = LocalDateTime.ofInstant(historicalInstant(now, (number % 12).toLong()), ZoneOffset.UTC),
                lastLoginIp = "192.0.2.${number.coerceAtMost(254)}",
                deliveryAddress = mutableListOf(
                    DeliveryAddressItem(
                        label = "Home",
                        name = "${profile.firstName} ${profile.lastName}",
                        phone = profile.phone,
                        country = profile.country,
                        stateOrProvince = profile.stateOrProvince,
                        city = profile.city,
                        postalCode = profile.postalCode,
                        address1 = "${100 + number} Demo Street",
                        isDefault = true,
                    ),
                ),
            )
        }

        val saved = userRepository.saveAllAndFlush(customers)
        val timestamps = saved.map { user ->
            val number = user.username.takeLast(3).toLong()
            val daysAgo = (number - 1) * CUSTOMER_INTERVAL_DAYS
            val createdAt = historicalInstant(now, daysAgo)
            TimestampRow(requireNotNull(user.id), createdAt, createdAt.plusSeconds(3_600))
        }
        backdateCreatedAndUpdated(
            "UPDATE users SET created_at = ?, updated_at = ? WHERE id = ?",
            timestamps,
        )
        return saved
    }

    /** 创建覆盖四种品类、上下架状态和低库存状态的商品目录。 */
    private fun seedProducts(now: Instant): List<Product> {
        val products = listOf(
            mockProduct(BikiniSuit(BikiniSuit.Size.S, BikiniSuit.Size.S), 1, "Lagoon Triangle Bikini", "Ocean Blue", "42.00", 28, 94),
            mockProduct(BikiniSuit(BikiniSuit.Size.M, BikiniSuit.Size.M), 2, "Coral Rib Bikini", "Coral", "48.00", 4, 81),
            mockProduct(BikiniSuit(BikiniSuit.Size.L, BikiniSuit.Size.L), 3, "Midnight Bandeau Bikini", "Black", "55.00", 0, 73),
            mockProduct(BikiniSuit(BikiniSuit.Size.XL, BikiniSuit.Size.XL), 4, "Palm Tie Bikini", "Palm Green", "51.00", 15, 62),
            mockProduct(OnePieceSuit(size = OnePieceSuit.Size.S), 5, "Coastline One Piece", "Navy", "69.00", 22, 88),
            mockProduct(OnePieceSuit(size = OnePieceSuit.Size.M, tummyControl = true), 6, "Sculpt Square Neck Suit", "Ruby", "78.00", 2, 76),
            mockProduct(OnePieceSuit(size = OnePieceSuit.Size.L, removablePadding = true), 7, "Harbor Cross Back Suit", "Teal", "74.00", 31, 69),
            mockProduct(OnePieceSuit(size = OnePieceSuit.Size.XL), 8, "Dune Plunge Suit", "Ivory", "72.00", 9, 57),
            mockProduct(Dress(size = Dress.Size.S, length = Dress.Length.MAXI), 9, "Sunset Linen Maxi Dress", "Terracotta", "86.00", 1, 66),
            mockProduct(Dress(size = Dress.Size.M, length = Dress.Length.MIDI), 10, "Marina Wrap Dress", "Cobalt", "79.00", 18, 54),
            mockProduct(Dress(size = Dress.Size.L, length = Dress.Length.MINI), 11, "Boardwalk Mini Dress", "White", "64.00", 12, 47),
            mockProduct(Dress(size = Dress.Size.XL, length = Dress.Length.MIDI), 12, "Garden Slip Dress", "Sage", "82.00", 3, 43),
            mockProduct(CoverUp(style = CoverUp.CoverUpStyle.KIMONO), 13, "Breeze Kimono Cover Up", "Sky", "45.00", 26, 51),
            mockProduct(CoverUp(style = CoverUp.CoverUpStyle.WRAP), 14, "Seaside Wrap Cover Up", "Sand", "49.00", 8, 39, Product.Status.INACTIVE),
            mockProduct(CoverUp(style = CoverUp.CoverUpStyle.TUNIC), 15, "Cotton Gauze Tunic", "Mint", "53.00", 17, 35, Product.Status.INACTIVE),
            mockProduct(CoverUp(style = CoverUp.CoverUpStyle.DUSTER), 16, "Longline Beach Duster", "Rose", "58.00", 6, 28, Product.Status.DELETED),
        )
        val saved = productRepository.saveAllAndFlush(products)
        backdateCreatedAndUpdated(
            "UPDATE products SET created_at = ?, updated_at = ? WHERE id = ?",
            saved.mapIndexed { index, product ->
                val createdAt = historicalInstant(now, 10L + index * 11L)
                TimestampRow(requireNotNull(product.id), createdAt, createdAt.plusSeconds(7_200))
            },
        )
        return saved
    }

    /** 创建每日一单的 180 天趋势数据，并为每张订单写入一条商品快照。 */
    private fun seedOrders(
        customers: List<User>,
        products: List<Product>,
        now: Instant,
    ): List<MockOrderRecord> {
        val pending = (0 until REPORT_HISTORY_DAYS).map { dayOffset ->
            val customer = customers[dayOffset % customers.size]
            val product = products[dayOffset % products.size]
            val quantity = dayOffset % 3 + 1
            val createdAt = historicalInstant(now, dayOffset.toLong())
            val status = ORDER_STATUSES[dayOffset % ORDER_STATUSES.size]
            val paidAt = createdAt.plusSeconds(3_600).takeIf { status in PAID_ORDER_STATUSES }
            val shippedAt = createdAt.plusSeconds(43_200).takeIf { status in SHIPPED_ORDER_STATUSES }
            val deliveredAt = createdAt.plusSeconds(129_600).takeIf { status in DELIVERED_ORDER_STATUSES }
            val cancelledAt = createdAt.plusSeconds(1_800).takeIf { status == OrderStatus.CANCELLED }
            val itemsSubtotal = product.price.multiply(BigDecimal.valueOf(quantity.toLong())).money()
            val shippingFee = if (itemsSubtotal >= FREE_SHIPPING_THRESHOLD) ZERO_MONEY else STANDARD_SHIPPING_FEE
            val taxAmount = itemsSubtotal.multiply(TAX_RATE).money()
            val discountAmount = if (dayOffset % 10 == 0) STANDARD_DISCOUNT else ZERO_MONEY
            val order = OrderEntity(
                orderNo = "MOCK-ORD-%06d".format(dayOffset + 1),
                customerId = requireNotNull(customer.id),
                status = status,
                itemsSubtotal = itemsSubtotal,
                shippingFee = shippingFee,
                taxAmount = taxAmount,
                discountAmount = discountAmount,
                totalAmount = itemsSubtotal.add(shippingFee).add(taxAmount).subtract(discountAmount).money(),
                currency = customer.currency ?: "USD",
                paymentIntentId = paidAt?.let { "pi_mock_%06d".format(dayOffset + 1) },
                shippingAddress = orderAddress(customer),
                clientMessage = "Please leave the parcel at reception.".takeIf { dayOffset % 11 == 0 },
                expiresAt = if (status == OrderStatus.PENDING_PAYMENT) now.plusSeconds(86_400) else createdAt.plusSeconds(1_800),
                paidAt = paidAt,
                shippedAt = shippedAt,
                deliveredAt = deliveredAt,
                cancelledAt = cancelledAt,
                cancelReason = "Customer changed their mind".takeIf { status == OrderStatus.CANCELLED },
            )
            PendingOrderRecord(order, product, quantity, dayOffset, createdAt)
        }
        orderRepository.saveAllAndFlush(pending.map(PendingOrderRecord::order))

        val records = pending.map { pendingOrder ->
            val product = pendingOrder.product
            val item = OrderItem(
                order = pendingOrder.order,
                productId = requireNotNull(product.id),
                productSnapshot = productSnapshot(product),
                unitPrice = product.price,
                quantity = pendingOrder.quantity,
                lineTotal = product.price.multiply(BigDecimal.valueOf(pendingOrder.quantity.toLong())).money(),
            )
            MockOrderRecord(
                order = pendingOrder.order,
                item = item,
                dayOffset = pendingOrder.dayOffset,
                createdAt = pendingOrder.createdAt,
                updatedAt = pendingOrder.order.deliveredAt
                    ?: pendingOrder.order.shippedAt
                    ?: pendingOrder.order.cancelledAt
                    ?: pendingOrder.order.paidAt
                    ?: pendingOrder.createdAt,
            )
        }
        orderItemRepository.saveAllAndFlush(records.map(MockOrderRecord::item))
        backdateCreatedAndUpdated(
            "UPDATE orders SET created_at = ?, updated_at = ? WHERE id = ?",
            records.map { TimestampRow(requireNotNull(it.order.id), it.createdAt, it.updatedAt) },
        )
        backdateCreated(
            "UPDATE order_items SET created_at = ? WHERE id = ?",
            records.map { TimestampRow(requireNotNull(it.item.id), it.createdAt) },
        )
        return records
    }

    /** 为已支付及后续状态订单创建运单和对应的发货明细。 */
    private fun seedShipments(
        orders: List<MockOrderRecord>,
        adminId: Long,
    ): Int {
        val records = orders
            .filter { it.order.status in SHIPMENT_ORDER_STATUSES }
            .map { orderRecord ->
                val status = shipmentStatus(orderRecord)
                val createdAt = orderRecord.createdAt.plusSeconds(7_200)
                val trackingNo = "MOCKTRACK%010d".format(orderRecord.dayOffset + 1)
                    .takeUnless { status == ShipmentStatus.LABEL_PENDING }
                val shippedAt = orderRecord.order.shippedAt
                    .takeIf { status in ACTIVE_DELIVERY_STATUSES }
                val deliveredAt = orderRecord.order.deliveredAt
                    .takeIf { status == ShipmentStatus.DELIVERED }
                val shipment = Shipment(
                    shipmentNo = "MOCK-SHP-%06d".format(orderRecord.dayOffset + 1),
                    orderId = requireNotNull(orderRecord.order.id),
                    carrierCode = CarrierCode.MANUAL,
                    trackingNo = trackingNo,
                    trackingNoNormalized = trackingNo,
                    status = status,
                    shippingAddress = orderRecord.order.shippingAddress.copySnapshot(),
                    shippedAt = shippedAt,
                    deliveredAt = deliveredAt,
                    trackingUrl = trackingNo?.let { "https://tracking.example.test/$it" },
                    lastTrackStatus = trackingNo?.let { status.name },
                    lastTrackAt = trackingNo?.let { deliveredAt ?: shippedAt ?: createdAt.plusSeconds(3_600) },
                    lastTrackEventId = trackingNo?.let { "mock-event-${orderRecord.dayOffset + 1}" },
                    lastTrackLocation = trackingNo?.let { orderRecord.order.shippingAddress.city },
                    consecutiveTrackFailures = if (orderRecord.dayOffset % 37 == 1) 2 else 0,
                    lastTrackError = "Temporary carrier timeout".takeIf { orderRecord.dayOffset % 37 == 1 },
                    createdBy = adminId,
                    cancelReason = "Label cancelled during mock fulfillment".takeIf {
                        status == ShipmentStatus.CANCELLED || status == ShipmentStatus.CANCEL_PENDING
                    },
                    note = "Generated mock shipment",
                )
                MockShipmentRecord(
                    shipment = shipment,
                    order = orderRecord,
                    createdAt = createdAt,
                    updatedAt = deliveredAt ?: shippedAt ?: createdAt.plusSeconds(3_600),
                )
            }
        shipmentRepository.saveAllAndFlush(records.map(MockShipmentRecord::shipment))
        val items = records.map { record ->
            ShipmentItem(
                shipment = record.shipment,
                orderItemId = requireNotNull(record.order.item.id),
                orderItemSnapshot = record.order.item.productSnapshot,
                quantity = record.order.item.quantity,
                allocationStatus = AllocationStatus.ALLOCATED,
            )
        }
        shipmentItemRepository.saveAllAndFlush(items)
        backdateCreatedAndUpdated(
            "UPDATE shipments SET created_at = ?, updated_at = ? WHERE id = ?",
            records.map { TimestampRow(requireNotNull(it.shipment.id), it.createdAt, it.updatedAt) },
        )
        backdateCreated(
            "UPDATE shipment_items SET created_at = ? WHERE id = ?",
            items.mapIndexed { index, item -> TimestampRow(requireNotNull(item.id), records[index].createdAt) },
        )
        return records.size
    }

    /** 创建不同状态和优先级的客服工单，并补充客户与管理员消息。 */
    private fun seedSupportTickets(
        customers: List<User>,
        admin: User,
        orders: List<MockOrderRecord>,
        now: Instant,
    ): Pair<Int, Int> {
        val ordersByCustomer = orders.groupBy { it.order.customerId }
        val records = (0 until SUPPORT_TICKET_COUNT).map { index ->
            val customer = customers[index % customers.size]
            val customerId = requireNotNull(customer.id)
            val status = SupportTicketStatus.entries[index % SupportTicketStatus.entries.size]
            val serviceType = if (index % 3 == 0) SupportServiceType.PRE_SALES else SupportServiceType.AFTER_SALES
            val linkedOrder = if (serviceType == SupportServiceType.AFTER_SALES) {
                ordersByCustomer.getValue(customerId)[index % ordersByCustomer.getValue(customerId).size].order
            } else {
                null
            }
            val createdAt = historicalInstant(now, index * 2L + 1)
            val repliedAt = createdAt.plusSeconds(10_800).takeUnless { status == SupportTicketStatus.OPEN }
            val resolvedAt = createdAt.plusSeconds(28_800).takeIf {
                status == SupportTicketStatus.RESOLVED || status == SupportTicketStatus.CLOSED
            }
            val closedAt = createdAt.plusSeconds(43_200).takeIf { status == SupportTicketStatus.CLOSED }
            val ticket = SupportTicket(
                customerId = customerId,
                serviceType = serviceType,
                priority = when {
                    index % 5 == 0 -> SupportTicketPriority.HIGH
                    index % 2 == 0 -> SupportTicketPriority.MEDIUM
                    else -> SupportTicketPriority.LOW
                },
                order = linkedOrder,
                subject = if (serviceType == SupportServiceType.PRE_SALES) {
                    "Sizing question #${index + 1}"
                } else {
                    "Order assistance #${index + 1}"
                },
                content = if (serviceType == SupportServiceType.PRE_SALES) {
                    "Could you help me choose the best size for this item?"
                } else {
                    "I need an update about the delivery of this order."
                },
                status = status,
                adminReply = "We are reviewing this request and will keep you updated."
                    .takeUnless { status == SupportTicketStatus.OPEN },
                handledBy = requireNotNull(admin.id).takeUnless { status == SupportTicketStatus.OPEN },
                repliedAt = repliedAt,
                resolvedAt = resolvedAt,
                closedAt = closedAt,
            )
            val updatedAt = closedAt ?: resolvedAt ?: repliedAt ?: createdAt
            MockTicketRecord(ticket, createdAt, updatedAt)
        }
        supportTicketRepository.saveAllAndFlush(records.map(MockTicketRecord::ticket))

        val messageRecords = records.flatMap { record ->
            val customerMessage = MockMessageRecord(
                SupportTicketMessage(
                    ticket = record.ticket,
                    senderId = record.ticket.customerId,
                    senderType = SupportTicketMessageSender.CUSTOMER,
                    content = record.ticket.content,
                ),
                record.createdAt.plusSeconds(300),
            )
            if (record.ticket.status == SupportTicketStatus.OPEN) {
                listOf(customerMessage)
            } else {
                listOf(
                    customerMessage,
                    MockMessageRecord(
                        SupportTicketMessage(
                            ticket = record.ticket,
                            senderId = requireNotNull(admin.id),
                            senderType = SupportTicketMessageSender.ADMIN,
                            content = requireNotNull(record.ticket.adminReply),
                        ),
                        requireNotNull(record.ticket.repliedAt),
                    ),
                )
            }
        }
        supportTicketMessageRepository.saveAllAndFlush(messageRecords.map(MockMessageRecord::message))
        backdateCreatedAndUpdated(
            "UPDATE support_tickets SET created_at = ?, updated_at = ? WHERE id = ?",
            records.map { TimestampRow(requireNotNull(it.ticket.id), it.createdAt, it.updatedAt) },
        )
        backdateCreated(
            "UPDATE support_ticket_messages SET created_at = ? WHERE id = ?",
            messageRecords.map { TimestampRow(requireNotNull(it.message.id), it.createdAt) },
        )
        return records.size to messageRecords.size
    }

    private fun <T : Product> mockProduct(
        product: T,
        number: Int,
        name: String,
        color: String,
        price: String,
        stock: Int,
        sales: Int,
        status: Product.Status = Product.Status.ACTIVE,
    ): T = product.apply {
        this.name = name
        this.color = color
        this.price = BigDecimal(price)
        warehouseVolume = stock
        salesVolume = sales
        this.status = status
        highlight = mutableListOf("Designed for all-day comfort", "Mock catalog item")
        images = mutableListOf("https://placehold.co/600x800/png?text=Mock+Product+$number")
        fitSense = "True to size with a comfortable fit"
        description = "Deterministic mock product used for local administration and dashboard testing."
        careInstructions = mutableListOf("Cold wash", "Dry flat")
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

    private fun orderAddress(customer: User): OrderShippingAddress {
        val address = customer.deliveryAddress.single()
        return OrderShippingAddress(
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
        )
    }

    private fun customerProfile(number: Int): CustomerProfile {
        val region = REGIONS[(number - 1) % REGIONS.size]
        return CustomerProfile(
            firstName = FIRST_NAMES[(number - 1) % FIRST_NAMES.size],
            lastName = LAST_NAMES[(number - 1) % LAST_NAMES.size],
            phone = region.phonePrefix + "%04d".format(number),
            locale = region.locale,
            currency = region.currency,
            country = region.country,
            stateOrProvince = region.stateOrProvince,
            city = region.city,
            postalCode = region.postalCode,
        )
    }

    private fun shipmentStatus(order: MockOrderRecord): ShipmentStatus = when (order.order.status) {
        OrderStatus.PAID -> PAID_SHIPMENT_STATUSES[(order.dayOffset / ORDER_STATUSES.size) % PAID_SHIPMENT_STATUSES.size]
        OrderStatus.SHIPPED -> if ((order.dayOffset / ORDER_STATUSES.size) % 2 == 0) {
            ShipmentStatus.IN_TRANSIT
        } else {
            ShipmentStatus.OUT_FOR_DELIVERY
        }
        OrderStatus.DELIVERED, OrderStatus.COMPLETED -> ShipmentStatus.DELIVERED
        else -> error("订单状态 ${order.order.status} 不应创建模拟运单")
    }

    private fun historicalInstant(now: Instant, daysAgo: Long): Instant {
        val startOfToday = now.truncatedTo(ChronoUnit.DAYS)
        val oneHourAgo = now.minus(Duration.ofHours(1))
        val currentDayBase = if (oneHourAgo.isBefore(startOfToday)) startOfToday else oneHourAgo
        return currentDayBase.minus(Duration.ofDays(daysAgo))
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private fun backdateCreatedAndUpdated(sql: String, rows: List<TimestampRow>) {
        jdbcTemplate.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(statement: PreparedStatement, index: Int) {
                val row = rows[index]
                statement.setTimestamp(1, Timestamp.from(row.createdAt))
                statement.setTimestamp(2, Timestamp.from(row.updatedAt))
                statement.setLong(3, row.id)
            }

            override fun getBatchSize(): Int = rows.size
        })
    }

    private fun backdateCreated(sql: String, rows: List<TimestampRow>) {
        jdbcTemplate.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(statement: PreparedStatement, index: Int) {
                val row = rows[index]
                statement.setTimestamp(1, Timestamp.from(row.createdAt))
                statement.setLong(2, row.id)
            }

            override fun getBatchSize(): Int = rows.size
        })
    }

    private data class PendingOrderRecord(
        val order: OrderEntity,
        val product: Product,
        val quantity: Int,
        val dayOffset: Int,
        val createdAt: Instant,
    )

    private data class MockOrderRecord(
        val order: OrderEntity,
        val item: OrderItem,
        val dayOffset: Int,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private data class MockShipmentRecord(
        val shipment: Shipment,
        val order: MockOrderRecord,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private data class MockTicketRecord(
        val ticket: SupportTicket,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private data class MockMessageRecord(val message: SupportTicketMessage, val createdAt: Instant)

    private data class TimestampRow(
        val id: Long,
        val createdAt: Instant,
        val updatedAt: Instant = createdAt,
    )

    private data class CustomerProfile(
        val firstName: String,
        val lastName: String,
        val phone: String,
        val locale: String,
        val currency: String,
        val country: String,
        val stateOrProvince: String,
        val city: String,
        val postalCode: String,
    )

    private data class Region(
        val phonePrefix: String,
        val locale: String,
        val currency: String,
        val country: String,
        val stateOrProvince: String,
        val city: String,
        val postalCode: String,
    )

    private data class AdminDefinition(
        val username: String,
        val email: String,
        val firstName: String,
        val lastName: String,
    )

    private companion object {
        const val SENTINEL_USERNAME = "mock_customer_001"
        const val CUSTOMER_COUNT = 36
        const val CUSTOMER_INTERVAL_DAYS = 5L
        const val REPORT_HISTORY_DAYS = 180
        const val SUPPORT_TICKET_COUNT = 24

        val ZERO_MONEY = BigDecimal("0.00")
        val STANDARD_SHIPPING_FEE = BigDecimal("7.95")
        val STANDARD_DISCOUNT = BigDecimal("5.00")
        val FREE_SHIPPING_THRESHOLD = BigDecimal("80.00")
        val TAX_RATE = BigDecimal("0.0825")

        val ADMIN_DEFINITIONS = listOf(
            AdminDefinition("admin", "mock.admin@shopmall.local", "Mock", "Administrator"),
            AdminDefinition("admin1", "mock.admin.02@shopmall.local", "Backup", "Administrator"),
        )

        val ORDER_STATUSES = listOf(
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
            OrderStatus.CANCELLED,
        )
        val PAID_ORDER_STATUSES = setOf(
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
        )
        val SHIPPED_ORDER_STATUSES = setOf(
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
        )
        val DELIVERED_ORDER_STATUSES = setOf(OrderStatus.DELIVERED, OrderStatus.COMPLETED)
        val SHIPMENT_ORDER_STATUSES = setOf(
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
        )
        val PAID_SHIPMENT_STATUSES = listOf(
            ShipmentStatus.LABEL_PENDING,
            ShipmentStatus.LABEL_CREATED,
            ShipmentStatus.CANCEL_PENDING,
            ShipmentStatus.CANCELLED,
        )
        val ACTIVE_DELIVERY_STATUSES = setOf(
            ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.OUT_FOR_DELIVERY,
            ShipmentStatus.DELIVERED,
        )

        val FIRST_NAMES = listOf(
            "Emma", "Olivia", "Ava", "Sophia", "Mia", "Amelia",
            "Harper", "Evelyn", "Luna", "Camila", "Sofia", "Aria",
        )
        val LAST_NAMES = listOf(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia",
            "Miller", "Davis", "Wilson", "Taylor", "Clark", "Lewis",
        )
        val REGIONS = listOf(
            Region("+1415555", "en-US", "USD", "US", "CA", "Los Angeles", "90001"),
            Region("+44207946", "en-GB", "GBP", "GB", "Greater London", "London", "SW1A 1AA"),
            Region("+49301234", "de-DE", "EUR", "DE", "Berlin", "Berlin", "10115"),
            Region("+6129374", "en-AU", "AUD", "AU", "NSW", "Sydney", "2000"),
        )
    }
}
