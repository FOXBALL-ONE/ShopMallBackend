package top.foxball.shopmall.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.AttributeValueType
import top.foxball.shopmall.entity.jdbc.BillingAddress
import top.foxball.shopmall.entity.jdbc.CareInstruction
import top.foxball.shopmall.entity.jdbc.CartItem
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationItem
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.LengthUnit
import top.foxball.shopmall.entity.jdbc.MaterialComponent
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderPaymentStatus
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductAttribute
import top.foxball.shopmall.entity.jdbc.ProductAttributeDefinition
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.entity.jdbc.ProductImage
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.entity.jdbc.ProductVariantAttribute
import top.foxball.shopmall.entity.jdbc.ReviewStatus
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.ShoppingCart
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicket
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.WeightUnit
import top.foxball.shopmall.repository.CustomerReviewRepository
import top.foxball.shopmall.repository.HomeRecommendationPlanRepository
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.SupportTicketMessageRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.OptionSignatureService
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/** 统一管理启动期的商品元数据和仅供本地开发使用的完整演示数据。 */
@Component
class MockDataInitializer(
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val productTypeRepository: ProductTypeRepository,
    private val productCategoryRepository: ProductCategoryRepository,
    private val tagRepository: TagRepository,
    private val customerReviewRepository: CustomerReviewRepository,
    private val homeRecommendationPlanRepository: HomeRecommendationPlanRepository,
    private val shoppingCartRepository: ShoppingCartRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val shipmentItemRepository: ShipmentItemRepository,
    private val supportTicketRepository: SupportTicketRepository,
    private val supportTicketMessageRepository: SupportTicketMessageRepository,
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
    private val optionSignatureService: OptionSignatureService,
    private val objectMapper: ObjectMapper,
    @Value($$"${shopmall.product-metadata.enabled:false}") private val productMetadataEnabled: Boolean,
    @Value($$"${shopmall.mock-data.enabled:false}") private val mockDataEnabled: Boolean,
    @Value($$"${shopmall.mock-data.password:MockData123!}") private val mockPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Order(0)
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun initialize() {
        if (productMetadataEnabled || mockDataEnabled) initializeProductMetadata()
        if (mockDataEnabled) initializeMockData()
    }

    private fun initializeProductMetadata() {
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
                jdbcTemplate.execute("select pg_advisory_xact_lock(734670001)")
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
        productMetadataDefinitions.forEachIndexed { index, source ->
            val type = productTypeRepository.findDetailedByCode(source.code) ?: ProductType(
                code = source.code,
                name = source.name,
                description = source.description,
                displayOrder = index,
            )
            source.attributes.forEachIndexed { attributeIndex, attribute ->
                if (type.attributeDefinitions.none { it.code == attribute.code }) {
                    type.attributeDefinitions += ProductAttributeDefinition(
                        productType = type,
                        code = attribute.code,
                        name = attribute.name,
                        scope = attribute.scope,
                        valueType = attribute.valueType,
                        required = attribute.required,
                        allowedValues = attribute.allowedValues.toMutableList(),
                        displayOrder = attributeIndex,
                    )
                }
            }
            productTypeRepository.save(type)
        }
        productMetadataCategories.forEachIndexed { index, source ->
            if (!productCategoryRepository.existsByCode(source.code)) {
                productCategoryRepository.save(
                    ProductCategory(
                        code = source.code,
                        name = source.name,
                        description = source.description,
                        displayOrder = index,
                    ),
                )
            }
        }
    }

    /**
     * 生成一套可直接覆盖客户站、管理端、仪表盘、订单履约和客服工作流的演示数据。
     * 已有任意商品时保持不写入，避免本地真实业务数据被演示数据混入。
     */
    private fun initializeMockData() {
        val now = LocalDateTime.now(ZoneOffset.UTC).withNano(0)
        val encodedPassword = requireNotNull(passwordEncoder.encode(mockPassword)) { "模拟账号密码编码失败" }
        val admins = ensureMockAdmins(encodedPassword)
        val priorMockDatasetExists = userRepository.findByUsername(MOCK_CUSTOMER_SENTINEL) != null
        if (productRepository.count() > 0) {
            if (priorMockDatasetExists) {
                val existingProducts = productRepository.findAll()
                val existingTags = tagRepository.findAll().associateBy(Tag::name)
                val recommendationPlanCount = seedHomeRecommendations(
                    existingProducts,
                    existingTags,
                    requireNotNull(admins.first().id),
                    now,
                )
                log.info(
                    "Mock catalog initialization skipped because products already exist; home recommendation plans initialized={}",
                    recommendationPlanCount,
                )
            } else {
                log.info("Mock catalog initialization skipped because products already exist")
            }
            return
        }
        if (priorMockDatasetExists) {
            log.warn("Mock catalog initialization skipped because a prior mock dataset sentinel exists")
            return
        }

        val customers = seedCustomers(encodedPassword, now)
        val tags = seedTags()
        val products = seedProducts(tags, now)
        val reviewCount = seedReviews(products, customers, now)
        val recommendationPlanCount = seedHomeRecommendations(
            products,
            tags,
            requireNotNull(admins.first().id),
            now,
        )
        val cartCount = seedCarts(products, customers)
        val orders = seedOrders(products, customers, now)
        val shipmentCount = seedShipments(orders, requireNotNull(admins.first().id), now)
        val ticketCounts = seedSupportTickets(orders, customers, requireNotNull(admins.first().id), now)

        log.info(
            "Mock data initialized: users={}, tags={}, products={}, variants={}, reviews={}, recommendationPlans={}, carts={}, orders={}, shipments={}, tickets={}, messages={}",
            admins.size + customers.size,
            tags.size,
            products.size,
            products.sumOf { it.variants.size },
            reviewCount,
            recommendationPlanCount,
            cartCount,
            orders.size,
            shipmentCount,
            ticketCounts.first,
            ticketCounts.second,
        )
    }

    /** 管理员每次启动均同步，确保干净数据库和已有模拟库都存在可登录管理账号。 */
    private fun ensureMockAdmins(encodedPassword: String): List<User> =
        MOCK_ADMIN_DEFINITIONS.map { definition ->
            val user = userRepository.findByUsername(definition.username) ?: User(username = definition.username)
            user.email = definition.email
            user.password = encodedPassword
            user.firstName = definition.firstName
            user.lastName = definition.lastName
            user.locale = "en-US"
            user.currency = "USD"
            user.emailVerified = true
            user.marketingConsent = false
            user.role = Role.ADMIN
            user.enabled = true
            user.status = Status.ACTIVE
            user
        }.let(userRepository::saveAllAndFlush)

    private fun seedCustomers(encodedPassword: String, now: LocalDateTime): List<User> {
        val customers = CUSTOMER_PROFILES.mapIndexed { index, profile ->
            val number = index + 1
            val hasMeasurements = number % 3 == 0
            val hasWeight = number % 4 == 0
            User(
                username = "mock_customer_%03d".format(number),
                email = "mock.customer.%03d@shopmall.local".format(number),
                password = encodedPassword,
                firstName = profile.firstName,
                lastName = profile.lastName,
                phone = profile.phone,
                locale = profile.locale,
                currency = "USD",
                birthday = LocalDate.of(1988 + number, (number % 12) + 1, (number % 26) + 1),
                bust = BigDecimal("34.00").add(BigDecimal.valueOf(number.toLong())).takeIf { hasMeasurements },
                waist = BigDecimal("26.00").add(BigDecimal.valueOf(number.toLong())).takeIf { hasMeasurements },
                hip = BigDecimal("36.00").add(BigDecimal.valueOf(number.toLong())).takeIf { hasMeasurements },
                torso = BigDecimal("58.00").add(BigDecimal.valueOf(number.toLong())).takeIf { hasMeasurements },
                braSize = "34".takeIf { hasMeasurements },
                cupSize = "C".takeIf { hasMeasurements },
                height = BigDecimal("65.00").add(BigDecimal.valueOf(number.toLong() % 5)).takeIf { hasMeasurements },
                lengthUnit = LengthUnit.INCH.takeIf { hasMeasurements },
                weight = BigDecimal("120.00").add(BigDecimal.valueOf(number.toLong() * 2)).takeIf { hasWeight },
                weightUnit = WeightUnit.LB.takeIf { hasWeight },
                emailVerified = true,
                marketingConsent = number % 3 == 0,
                enabled = number != CUSTOMER_PROFILES.size,
                status = if (number == CUSTOMER_PROFILES.size) Status.INACTIVE else Status.ACTIVE,
                lastLoginAt = now.minusDays((number * 3L) % 31).minusHours(number.toLong()),
                lastLoginIp = "192.0.2.${10 + number}",
                deliveryAddress = mutableListOf(
                    DeliveryAddressItem(
                        label = "Home",
                        name = "${profile.firstName} ${profile.lastName}",
                        phone = profile.phone,
                        country = profile.country,
                        stateOrProvince = profile.stateOrProvince,
                        city = profile.city,
                        postalCode = profile.postalCode,
                        address1 = "${110 + number} ${profile.street}",
                        isDefault = true,
                        deliveryInstructions = "Leave at the front desk".takeIf { number % 4 == 0 },
                    ),
                ),
                billingAddress = BillingAddress(
                    name = "${profile.firstName} ${profile.lastName}",
                    country = profile.country,
                    stateOrProvince = profile.stateOrProvince,
                    city = profile.city,
                    postalCode = profile.postalCode,
                    address1 = "${110 + number} ${profile.street}",
                ).takeIf { number % 2 == 0 },
            )
        }
        val saved = userRepository.saveAllAndFlush(customers)
        updateCreatedAndUpdated(
            "users",
            saved.mapIndexed { index, user ->
                val createdAt = now.minusDays(8L + index * 9L)
                TimestampRow(requireNotNull(user.id), createdAt, createdAt.plusHours(2))
            },
        )
        return saved
    }

    private fun seedTags(): Map<String, Tag> {
        val existingByName = tagRepository.findAll().associateBy(Tag::name)
        val tags = TAG_DEFINITIONS.mapIndexed { index, definition ->
            (existingByName[definition.name] ?: Tag(name = definition.name)).apply {
                description = definition.description
                color = definition.color
                sortOrder = index
                active = definition.active
            }
        }
        return tagRepository.saveAllAndFlush(tags).associateBy(Tag::name)
    }

    private fun seedProducts(tags: Map<String, Tag>, now: LocalDateTime): List<Product> {
        val types = CATALOG_DEFINITIONS.map(CatalogDefinition::typeCode).distinct().associateWith { code ->
            requireNotNull(productTypeRepository.findDetailedByCode(code)) { "缺少模拟商品类型: $code" }
        }
        val categories = productCategoryRepository.findAllByOrderByDisplayOrderAscNameAsc().associateBy(ProductCategory::code)
        val products = CATALOG_DEFINITIONS.mapIndexed { index, definition ->
            val product = Product(
                productType = types.getValue(definition.typeCode),
                category = requireNotNull(categories[definition.categoryCode]) { "缺少模拟商品分类: ${definition.categoryCode}" },
                name = definition.name,
                status = definition.status,
                materials = definition.materials.map { MaterialComponent(it.name, it.percentage) }.toMutableList(),
                attributes = definition.attributes.map { ProductAttribute(it.first, it.second) }.toMutableList(),
                highlights = definition.highlights.toMutableList(),
                images = productImages(definition.imageKey, definition.name),
                fitSense = definition.fitSense,
                description = definition.description,
                designAndExtras = definition.designAndExtras.toMutableList(),
                careInstructions = CARE_INSTRUCTIONS.map(::CareInstruction).toMutableList(),
                tags = definition.tagNames.map(tags::getValue).toMutableSet(),
                deletedAt = now.minusDays(14L + index).takeIf { definition.deleted },
            )
            createVariants(definition, index + 1).forEach(product::addVariant)
            product
        }
        val saved = productRepository.saveAllAndFlush(products)
        updateCreatedAndUpdated(
            "products",
            saved.mapIndexed { index, product ->
                val createdAt = now.minusDays(4L + index * 4L)
                TimestampRow(requireNotNull(product.id), createdAt, createdAt.plusHours((index % 7 + 1).toLong()))
            },
        )
        return saved
    }

    private fun productImages(imageKey: String, name: String): MutableList<ProductImage> =
        (1..3).map { number ->
            ProductImage(
                url = "https://placehold.co/900x1200/png?text=$imageKey-$number",
                altText = "$name product view $number",
                primary = number == 1,
            )
        }.toMutableList()

    /** 比基尼展示颜色与上下装尺码组合，其余商品展示两个颜色下的常规尺码 SKU。 */
    private fun createVariants(definition: CatalogDefinition, productNumber: Int): List<ProductVariant> =
        if (definition.typeCode == "BIKINI") {
            definition.colors.flatMapIndexed { colorIndex, color ->
                BIKINI_SIZE_PAIRS.mapIndexed { sizeIndex, (topSize, bottomSize) ->
                    val attributes = mutableListOf(
                        ProductVariantAttribute("top_size", topSize),
                        ProductVariantAttribute("bottom_size", bottomSize),
                    )
                    productVariant(
                        definition = definition,
                        productNumber = productNumber,
                        variantIndex = colorIndex * BIKINI_SIZE_PAIRS.size + sizeIndex,
                        size = "$topSize/$bottomSize",
                        color = color,
                        attributes = attributes,
                        price = definition.basePrice.add(BigDecimal.valueOf((colorIndex * 2L))),
                    )
                }
            }
        } else {
            STANDARD_SIZES.mapIndexed { sizeIndex, size ->
                val color = definition.colors[sizeIndex / 2]
                productVariant(
                    definition = definition,
                    productNumber = productNumber,
                    variantIndex = sizeIndex,
                    size = size,
                    color = color,
                    attributes = mutableListOf(),
                    price = definition.basePrice.add(BigDecimal.valueOf((sizeIndex / 2 * 2L))),
                )
            }
        }

    private fun productVariant(
        definition: CatalogDefinition,
        productNumber: Int,
        variantIndex: Int,
        size: String,
        color: String,
        attributes: MutableList<ProductVariantAttribute>,
        price: BigDecimal,
    ): ProductVariant {
        val activeProduct = definition.status == Product.Status.ACTIVE && !definition.deleted
        val status = if (!activeProduct || (variantIndex == 0 && productNumber % 6 == 0)) {
            ProductVariant.Status.INACTIVE
        } else {
            ProductVariant.Status.ACTIVE
        }
        val warehouseVolume = when {
            activeProduct && variantIndex == 0 && productNumber % 5 == 0 -> 0
            activeProduct && variantIndex == 1 && productNumber % 4 == 0 -> 3
            else -> 12 + (productNumber * 7 + variantIndex * 5) % 46
        }
        val skuSize = size.replace('/', '-')
        return ProductVariant(
            sku = "MOCK-${definition.skuCode}-$color-$skuSize",
            size = size,
            color = color,
            price = price.setScale(2, RoundingMode.UNNECESSARY),
            warehouseVolume = warehouseVolume,
            salesVolume = 18L + productNumber * 11L + variantIndex * 7L,
            displayOrder = variantIndex,
            status = status,
            attributes = attributes,
            optionSignature = optionSignatureService.generate(size, color, attributes),
        )
    }

    private fun seedReviews(products: List<Product>, customers: List<User>, now: LocalDateTime): Int {
        val reviews = products.filter { it.status == Product.Status.ACTIVE && it.deletedAt == null }.flatMapIndexed { productIndex, product ->
            val approved = (0..1).map { offset ->
                val rating = 4 + (productIndex + offset) % 2
                CustomerReview(
                    product = product,
                    customerId = requireNotNull(customers[(productIndex * 2 + offset) % customers.size].id),
                    rating = rating,
                    title = if (rating == 5) "Beautiful fit" else "Comfortable and well made",
                    content = "The color, fabric, and fit matched the product description. I would order this style again.",
                    verifiedPurchase = true,
                    status = ReviewStatus.APPROVED,
                    merchantReply = "Thank you for sharing your experience.".takeIf { offset == 0 && productIndex % 3 == 0 },
                    merchantRepliedAt = now.minusDays((productIndex + 1).toLong()).takeIf { offset == 0 && productIndex % 3 == 0 },
                )
            }
            val pending = CustomerReview(
                product = product,
                customerId = requireNotNull(customers[(productIndex + 5) % customers.size].id),
                rating = 4,
                title = "Fresh feedback",
                content = "Newly submitted feedback awaiting moderation for the administration workflow.",
                verifiedPurchase = productIndex % 2 == 0,
                status = ReviewStatus.PENDING,
            ).takeIf { productIndex % 3 == 0 }
            approved + listOfNotNull(pending)
        }
        customerReviewRepository.saveAllAndFlush(reviews)
        products.forEach { product ->
            val approved = reviews.filter { it.product === product && it.status == ReviewStatus.APPROVED }
            product.score = approved.map(CustomerReview::rating).average().toFloat().takeIf { approved.isNotEmpty() }
        }
        productRepository.saveAll(products)
        return reviews.size
    }

    /** 为客户首页和管理端生成已发布、待发布及草稿三种运营方案。 */
    private fun seedHomeRecommendations(
        products: List<Product>,
        tags: Map<String, Tag>,
        adminId: Long,
        now: LocalDateTime,
    ): Int {
        if (homeRecommendationPlanRepository.count() > 0) return 0

        val sellableProducts = products.filter { product ->
            product.id != null && product.status == Product.Status.ACTIVE && product.deletedAt == null &&
                product.images.isNotEmpty() && product.variants.any { variant ->
                    variant.id != null && variant.status == ProductVariant.Status.ACTIVE &&
                        variant.warehouseVolume > 0 && variant.price.signum() > 0
                }
        }
        if (sellableProducts.isEmpty()) {
            log.warn("Mock home recommendation initialization skipped because no sellable products exist")
            return 0
        }

        val swimwearProducts = sellableProducts.filter { it.productType?.code in setOf("BIKINI", "ONE_PIECE") }
            .ifEmpty { sellableProducts }
        val editorProducts = (swimwearProducts + sellableProducts).distinctBy(Product::id).take(8)
        val bestSellerTagId = tags["Best Seller"]?.id
        val newArrivalTagId = tags["New Arrival"]?.id

        val publishedPlan = HomeRecommendationPlan(
            name = "Mock 夏日首页精选",
            status = HomeRecommendationPlan.Status.PUBLISHED,
            effectiveFrom = now.minusDays(1),
            effectiveUntil = now.plusDays(14),
            fallbackEnabled = true,
            deduplicateAcrossSections = true,
            createdBy = adminId,
            updatedBy = adminId,
            publishedAt = now.minusHours(2),
        ).apply {
            replaceSections(
                listOf(
                    HomeRecommendationSection(
                        code = "editor_picks",
                        eyebrow = "CURATED FOR YOU",
                        title = "Summer Editor Picks",
                        subtitle = "Swim and resort styles selected for the season.",
                        displayStyle = HomeRecommendationSection.DisplayStyle.GRID,
                        desktopColumns = 4,
                        mobileColumns = 2,
                        linkLabel = "Shop swimwear",
                        linkUrl = "/collections/swimwear",
                        itemLimit = 8,
                        sortOrder = 0,
                    ).also { section ->
                        section.replaceGroups(
                            listOf(
                                HomeRecommendationGroup(
                                    code = "featured_swim",
                                    title = "Featured Swim",
                                    selectionMode = HomeRecommendationGroup.SelectionMode.HYBRID,
                                    strategy = HomeRecommendationGroup.Strategy.EDITOR_PICKS,
                                    itemLimit = 8,
                                    categoryId = swimwearProducts.firstNotNullOfOrNull { it.category?.id },
                                    minimumStock = 1,
                                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.BEST_SELLERS,
                                    sortOrder = 0,
                                ).also { group ->
                                    group.replaceItems(
                                        editorProducts.mapIndexed { index, product ->
                                            HomeRecommendationItem(
                                                productId = requireNotNull(product.id),
                                                pinned = index < 2,
                                                customBadge = when (index) {
                                                    0 -> "EDITOR'S PICK"
                                                    1 -> "TRENDING"
                                                    else -> null
                                                },
                                                sortOrder = index,
                                            )
                                        },
                                    )
                                },
                            ),
                        )
                    },
                    HomeRecommendationSection(
                        code = "shop_the_latest",
                        eyebrow = "DISCOVER WHAT'S NEW",
                        title = "Shop the Latest",
                        subtitle = "Fresh arrivals, customer favorites, and top-rated styles.",
                        displayStyle = HomeRecommendationSection.DisplayStyle.TABS,
                        desktopColumns = 4,
                        mobileColumns = 2,
                        linkLabel = "View all products",
                        linkUrl = "/collections/shop",
                        itemLimit = 8,
                        sortOrder = 1,
                    ).also { section ->
                        section.replaceGroups(
                            listOf(
                                HomeRecommendationGroup(
                                    code = "new_arrivals",
                                    title = "New Arrivals",
                                    selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                                    strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
                                    itemLimit = 8,
                                    tagId = newArrivalTagId,
                                    lookbackDays = 180,
                                    minimumStock = 1,
                                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.LATEST,
                                    sortOrder = 0,
                                ),
                                HomeRecommendationGroup(
                                    code = "best_sellers",
                                    title = "Best Sellers",
                                    selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                                    strategy = HomeRecommendationGroup.Strategy.BEST_SELLERS,
                                    itemLimit = 8,
                                    tagId = bestSellerTagId,
                                    lookbackDays = null,
                                    minimumStock = 1,
                                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.LATEST,
                                    sortOrder = 1,
                                ),
                                HomeRecommendationGroup(
                                    code = "top_rated",
                                    title = "Top Rated",
                                    selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                                    strategy = HomeRecommendationGroup.Strategy.HIGH_RATED,
                                    itemLimit = 8,
                                    lookbackDays = null,
                                    minimumStock = 1,
                                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.BEST_SELLERS,
                                    sortOrder = 2,
                                ),
                            ),
                        )
                    },
                ),
            )
        }

        val scheduledPlan = HomeRecommendationPlan(
            name = "Mock 假日度假专题",
            status = HomeRecommendationPlan.Status.SCHEDULED,
            effectiveFrom = now.plusDays(14),
            effectiveUntil = now.plusDays(30),
            fallbackEnabled = true,
            deduplicateAcrossSections = true,
            createdBy = adminId,
            updatedBy = adminId,
        ).apply {
            replaceSections(
                listOf(
                    HomeRecommendationSection(
                        code = "resort_collection",
                        eyebrow = "COMING NEXT",
                        title = "The Resort Edit",
                        subtitle = "Vacation-ready swimwear, dresses, and effortless layers.",
                        displayStyle = HomeRecommendationSection.DisplayStyle.CAROUSEL,
                        desktopColumns = 4,
                        mobileColumns = 2,
                        linkLabel = "Explore resort styles",
                        linkUrl = "/collections/shop",
                        itemLimit = 10,
                    ).also { section ->
                        section.replaceGroups(
                            listOf(
                                HomeRecommendationGroup(
                                    code = "resort_favorites",
                                    title = "Resort Favorites",
                                    selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                                    strategy = HomeRecommendationGroup.Strategy.BEST_SELLERS,
                                    itemLimit = 10,
                                    tagId = tags["Resort Ready"]?.id,
                                    lookbackDays = null,
                                    minimumStock = 1,
                                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.LATEST,
                                ),
                            ),
                        )
                    },
                ),
            )
        }

        val draftPlan = HomeRecommendationPlan(
            name = "Mock 首页推荐实验草稿",
            status = HomeRecommendationPlan.Status.DRAFT,
            effectiveFrom = now.plusDays(31),
            fallbackEnabled = true,
            deduplicateAcrossSections = false,
            createdBy = adminId,
            updatedBy = adminId,
        ).apply {
            replaceSections(
                listOf(
                    HomeRecommendationSection(
                        code = "staff_favorites",
                        eyebrow = "DRAFT PREVIEW",
                        title = "Staff Favorites",
                        subtitle = "A draft manual collection for testing preview and editing.",
                        displayStyle = HomeRecommendationSection.DisplayStyle.GRID,
                        desktopColumns = 3,
                        mobileColumns = 1,
                        itemLimit = 6,
                    ).also { section ->
                        section.replaceGroups(
                            listOf(
                                HomeRecommendationGroup(
                                    code = "manual_selection",
                                    title = "Manual Selection",
                                    selectionMode = HomeRecommendationGroup.SelectionMode.MANUAL,
                                    strategy = HomeRecommendationGroup.Strategy.EDITOR_PICKS,
                                    itemLimit = 6,
                                    lookbackDays = null,
                                    minimumStock = 1,
                                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.NONE,
                                ).also { group ->
                                    group.replaceItems(
                                        sellableProducts.takeLast(6).mapIndexed { index, product ->
                                            HomeRecommendationItem(
                                                productId = requireNotNull(product.id),
                                                pinned = index == 0,
                                                customBadge = "STAFF PICK".takeIf { index == 0 },
                                                sortOrder = index,
                                            )
                                        },
                                    )
                                },
                            ),
                        )
                    },
                ),
            )
        }

        return homeRecommendationPlanRepository.saveAllAndFlush(listOf(publishedPlan, scheduledPlan, draftPlan)).size
    }

    private fun seedCarts(products: List<Product>, customers: List<User>): Int {
        val variants = activeInStockVariants(products)
        val carts = customers.take(8).mapIndexed { customerIndex, customer ->
            ShoppingCart(customer = customer).also { cart ->
                (0 until if (customerIndex % 3 == 0) 2 else 1).forEach { offset ->
                    cart.add(
                        CartItem(
                            variant = variants[(customerIndex * 3 + offset) % variants.size],
                            quantity = offset + 1,
                        ),
                    )
                }
            }
        }
        shoppingCartRepository.saveAllAndFlush(carts)
        return carts.size
    }

    private fun seedOrders(
        products: List<Product>,
        customers: List<User>,
        now: LocalDateTime,
    ): List<MockOrderRecord> {
        val variants = activeInStockVariants(products)
        val drafts = (1..MOCK_ORDER_COUNT).map { number ->
            val status = MOCK_ORDER_STATUSES[(number - 1) % MOCK_ORDER_STATUSES.size]
            val createdAt = if (status == OrderStatus.PENDING_PAYMENT) {
                now.minusMinutes((number * 11L) % 180)
            } else {
                now.minusDays((number % 60).toLong()).minusHours((number * 3L) % 20)
            }
            val customer = customers[(number - 1) % (customers.size - 1)]
            val variant = variants[(number * 5) % variants.size]
            val quantity = number % 3 + 1
            val subtotal = money(variant.price.multiply(BigDecimal.valueOf(quantity.toLong())))
            val shippingFee = if (subtotal >= FREE_SHIPPING_THRESHOLD) ZERO_MONEY else BigDecimal("7.50")
            val taxAmount = money(subtotal.multiply(BigDecimal("0.08")))
            val discountAmount = BigDecimal("5.00").takeIf { number % 9 == 0 } ?: ZERO_MONEY
            val paidAt = createdAt.plusHours(1).toInstant(ZoneOffset.UTC).takeIf { status in PAID_ORDER_STATUSES }
            val shippedAt = createdAt.plusHours(16).toInstant(ZoneOffset.UTC).takeIf { status in SHIPPED_ORDER_STATUSES }
            val deliveredAt = createdAt.plusDays(4).toInstant(ZoneOffset.UTC).takeIf { status in DELIVERED_ORDER_STATUSES }
            val cancelledAt = createdAt.plusMinutes(25).toInstant(ZoneOffset.UTC).takeIf { status == OrderStatus.CANCELLED }
            val order = OrderEntity(
                orderNo = "MOCK-ORD-%05d".format(number),
                customerId = requireNotNull(customer.id),
                status = status,
                paymentStatus = paymentStatus(status),
                itemsSubtotal = subtotal,
                shippingFee = shippingFee,
                taxAmount = taxAmount,
                discountAmount = discountAmount,
                totalAmount = money(subtotal + shippingFee + taxAmount - discountAmount),
                currency = "USD",
                paymentIntentId = "pi_mock_%05d".format(number).takeIf { status in PAID_ORDER_STATUSES },
                shippingAddress = shippingAddress(customer),
                clientMessage = "Please leave the parcel at reception.".takeIf { number % 11 == 0 },
                expiresAt = now.plusHours(4).toInstant(ZoneOffset.UTC).takeIf { status == OrderStatus.PENDING_PAYMENT },
                paidAt = paidAt,
                refundRequestedAt = createdAt.plusDays(2).takeIf { status == OrderStatus.REFUNDING || status == OrderStatus.REFUNDED },
                refundedAt = createdAt.plusDays(3).takeIf { status == OrderStatus.REFUNDED },
                cancelledAt = cancelledAt,
                shippedAt = shippedAt,
                deliveredAt = deliveredAt,
                cancelReason = "Customer changed their mind".takeIf { status == OrderStatus.CANCELLED },
            )
            MockOrderDraft(order, customer, variant, quantity, createdAt)
        }
        val savedOrders = orderRepository.saveAllAndFlush(drafts.map(MockOrderDraft::order)).associateBy(OrderEntity::orderNo)
        val records = drafts.map { draft ->
            val order = savedOrders.getValue(draft.order.orderNo)
            val item = OrderItem(
                order = order,
                productId = requireNotNull(draft.variant.product?.id),
                variantId = requireNotNull(draft.variant.id),
                sku = draft.variant.sku,
                productSnapshot = productSnapshot(draft.variant),
                unitPrice = draft.variant.price,
                quantity = draft.quantity,
                lineTotal = money(draft.variant.price.multiply(BigDecimal.valueOf(draft.quantity.toLong()))),
            )
            MockOrderRecord(order, item, draft.customer, draft.createdAt)
        }
        orderItemRepository.saveAllAndFlush(records.map(MockOrderRecord::item))
        updateCreatedAndUpdated(
            "orders",
            records.map { record ->
                val updatedAt = record.order.deliveredAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: record.order.shippedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: record.order.paidAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: record.createdAt
                TimestampRow(requireNotNull(record.order.id), record.createdAt, updatedAt)
            },
        )
        updateCreated(
            "order_items",
            records.map { record -> TimestampRow(requireNotNull(record.item.id), record.createdAt, record.createdAt) },
        )
        return records
    }

    private fun activeInStockVariants(products: List<Product>): List<ProductVariant> =
        products.asSequence()
            .filter { it.status == Product.Status.ACTIVE && it.deletedAt == null }
            .flatMap { it.variants.asSequence() }
            .filter { it.status == ProductVariant.Status.ACTIVE && it.warehouseVolume >= 8 }
            .toList()

    private fun paymentStatus(status: OrderStatus): OrderPaymentStatus = when (status) {
        OrderStatus.PENDING_PAYMENT -> OrderPaymentStatus.PENDING_PAYMENT
        OrderStatus.CANCELLED -> OrderPaymentStatus.CANCELLED
        OrderStatus.REFUNDING -> OrderPaymentStatus.REFUNDING
        OrderStatus.REFUNDED -> OrderPaymentStatus.REFUNDED
        else -> OrderPaymentStatus.PAID
    }

    private fun shippingAddress(customer: User): OrderShippingAddress {
        val address = requireNotNull(customer.deliveryAddress.firstOrNull()) { "模拟客户缺少收货地址" }
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

    private fun productSnapshot(variant: ProductVariant): String {
        val product = requireNotNull(variant.product) { "模拟 SKU 缺少商品" }
        return objectMapper.writeValueAsString(
            linkedMapOf(
                "productId" to product.id,
                "variantId" to variant.id,
                "sku" to variant.sku,
                "productType" to requireNotNull(product.productType?.code),
                "name" to product.name,
                "color" to variant.color,
                "size" to variant.size,
                "currency" to "USD",
                "attributes" to product.attributes.associate { it.code to it.value },
                "variantAttributes" to variant.attributes.associate { it.code to it.value },
                "primaryImage" to product.images.firstOrNull(ProductImage::primary)?.url,
            ),
        )
    }

    private fun seedShipments(orders: List<MockOrderRecord>, adminId: Long, now: LocalDateTime): Int {
        val drafts = orders.filter { it.order.status in SHIPMENT_ORDER_STATUSES }.mapIndexed { index, record ->
            val status = when (record.order.status) {
                OrderStatus.PAID -> ShipmentStatus.LABEL_CREATED
                OrderStatus.SHIPPED -> if (index % 3 == 0) ShipmentStatus.OUT_FOR_DELIVERY else ShipmentStatus.IN_TRANSIT
                OrderStatus.DELIVERED, OrderStatus.COMPLETED -> ShipmentStatus.DELIVERED
                else -> error("不应为该订单状态创建模拟运单")
            }
            val trackingNo = "MOCKTRACK%010d".format(index + 1)
            val shipment = Shipment(
                shipmentNo = "MOCK-SHP-%05d".format(index + 1),
                orderId = requireNotNull(record.order.id),
                trackingNo = trackingNo,
                trackingNoNormalized = trackingNo,
                status = status,
                shippingAddress = record.order.shippingAddress.copySnapshot(),
                shippedAt = record.order.shippedAt,
                deliveredAt = record.order.deliveredAt,
                carrierLabelUrl = "https://labels.example.test/$trackingNo.pdf",
                trackingUrl = "https://tracking.example.test/$trackingNo",
                lastTrackStatus = status.name,
                lastTrackAt = (record.order.deliveredAt ?: record.order.shippedAt ?: now.toInstant(ZoneOffset.UTC)),
                lastTrackEventId = "mock-event-${index + 1}",
                lastTrackLocation = record.order.shippingAddress.city,
                consecutiveTrackFailures = if (index % 17 == 0) 1 else 0,
                lastTrackError = "Temporary carrier timeout".takeIf { index % 17 == 0 },
                createdBy = adminId,
                note = "Generated mock fulfillment record",
            )
            MockShipmentDraft(shipment, record)
        }
        val saved = shipmentRepository.saveAllAndFlush(drafts.map(MockShipmentDraft::shipment)).associateBy(Shipment::shipmentNo)
        val items = drafts.map { draft ->
            ShipmentItem(
                shipment = saved.getValue(draft.shipment.shipmentNo),
                orderItemId = requireNotNull(draft.order.item.id),
                orderItemSnapshot = draft.order.item.productSnapshot,
                quantity = draft.order.item.quantity,
            )
        }
        shipmentItemRepository.saveAllAndFlush(items)
        updateCreatedAndUpdated(
            "shipments",
            drafts.map { draft ->
                val updatedAt = draft.order.order.deliveredAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: draft.order.order.shippedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: draft.order.createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
                    ?: now
                TimestampRow(requireNotNull(saved.getValue(draft.shipment.shipmentNo).id), draft.order.createdAt.plusHours(2), updatedAt)
            },
        )
        updateCreated(
            "shipment_items",
            items.mapIndexed { index, item ->
                TimestampRow(requireNotNull(item.id), drafts[index].order.createdAt.plusHours(2), drafts[index].order.createdAt.plusHours(2))
            },
        )
        return drafts.size
    }

    private fun seedSupportTickets(
        orders: List<MockOrderRecord>,
        customers: List<User>,
        adminId: Long,
        now: LocalDateTime,
    ): Pair<Int, Int> {
        val drafts = (0 until MOCK_TICKET_COUNT).map { index ->
            val afterSales = index % 3 != 0
            val record = orders[(index * 5) % orders.size]
            val customer = if (afterSales) record.customer else customers[index % (customers.size - 1)]
            val status = SupportTicketStatus.entries[index % SupportTicketStatus.entries.size]
            val createdAt = now.minusDays((index * 3L) + 1)
            val repliedAt = createdAt.plusHours(3).toInstant(ZoneOffset.UTC).takeUnless { status == SupportTicketStatus.OPEN }
            val resolvedAt = createdAt.plusHours(12).toInstant(ZoneOffset.UTC).takeIf {
                status == SupportTicketStatus.RESOLVED || status == SupportTicketStatus.CLOSED
            }
            val closedAt = createdAt.plusHours(18).toInstant(ZoneOffset.UTC).takeIf { status == SupportTicketStatus.CLOSED }
            val ticket = SupportTicket(
                customerId = requireNotNull(customer.id),
                serviceType = if (afterSales) SupportServiceType.AFTER_SALES else SupportServiceType.PRE_SALES,
                priority = when {
                    index % 5 == 0 -> SupportTicketPriority.HIGH
                    index % 2 == 0 -> SupportTicketPriority.MEDIUM
                    else -> SupportTicketPriority.LOW
                },
                order = record.order.takeIf { afterSales },
                subject = if (afterSales) "Order assistance request #${index + 1}" else "Sizing question #${index + 1}",
                content = if (afterSales) {
                    "Please provide an update on the delivery and fulfillment details for this order."
                } else {
                    "Could you help me choose the best size for this product style?"
                },
                status = status,
                adminReply = "We reviewed your request and will keep you updated.".takeUnless { status == SupportTicketStatus.OPEN },
                handledBy = adminId.takeUnless { status == SupportTicketStatus.OPEN },
                repliedAt = repliedAt,
                resolvedAt = resolvedAt,
                closedAt = closedAt,
            )
            MockTicketDraft(ticket, createdAt)
        }
        val savedTickets = supportTicketRepository.saveAllAndFlush(drafts.map(MockTicketDraft::ticket))
        val messages = drafts.flatMapIndexed { index, draft ->
            val ticket = savedTickets[index]
            val customerMessage = SupportTicketMessage(
                ticket = ticket,
                senderId = ticket.customerId,
                senderType = SupportTicketMessageSender.CUSTOMER,
                content = ticket.content,
            )
            if (ticket.status == SupportTicketStatus.OPEN) {
                listOf(customerMessage)
            } else {
                listOf(
                    customerMessage,
                    SupportTicketMessage(
                        ticket = ticket,
                        senderId = adminId,
                        senderType = SupportTicketMessageSender.ADMIN,
                        content = requireNotNull(ticket.adminReply),
                    ),
                )
            }
        }
        supportTicketMessageRepository.saveAllAndFlush(messages)
        val ticketCreatedAt = savedTickets.mapIndexed { index, ticket ->
            requireNotNull(ticket.id) to drafts[index].createdAt
        }.toMap()
        updateCreatedAndUpdated(
            "support_tickets",
            drafts.mapIndexed { index, draft ->
                val ticket = savedTickets[index]
                val updatedAt = ticket.closedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: ticket.resolvedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: ticket.repliedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                    ?: draft.createdAt
                TimestampRow(requireNotNull(ticket.id), draft.createdAt, updatedAt)
            },
        )
        updateCreated(
            "support_ticket_messages",
            messages.map { message ->
                val createdAt = ticketCreatedAt.getValue(requireNotNull(message.ticket?.id))
                    .plusMinutes(if (message.senderType == SupportTicketMessageSender.ADMIN) 30 else 5)
                TimestampRow(requireNotNull(message.id), createdAt, createdAt)
            },
        )
        return savedTickets.size to messages.size
    }

    private fun updateCreatedAndUpdated(table: String, rows: List<TimestampRow>) {
        if (rows.isEmpty()) return
        jdbcTemplate.batchUpdate(
            "update $table set created_at = ?, updated_at = ? where id = ?",
            rows,
            100,
        ) { statement, row ->
            statement.setObject(1, row.createdAt)
            statement.setObject(2, row.updatedAt)
            statement.setLong(3, row.id)
        }
    }

    private fun updateCreated(table: String, rows: List<TimestampRow>) {
        if (rows.isEmpty()) return
        jdbcTemplate.batchUpdate(
            "update $table set created_at = ? where id = ?",
            rows,
            100,
        ) { statement, row ->
            statement.setObject(1, row.createdAt)
            statement.setLong(2, row.id)
        }
    }

    private fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    private val sizes = listOf("S", "M", "L", "XL", "XXL", "XXXL", "XXXXL")

    private val productMetadataDefinitions = listOf(
        TypeDefinition(
            "BIKINI", "Bikini", "Two-piece swimwear",
            listOf(
                attribute("top_size", "Top size", AttributeScope.VARIANT, true, sizes),
                attribute("bottom_size", "Bottom size", AttributeScope.VARIANT, true, sizes),
                attribute("cup_style", "Cup style", AttributeScope.PRODUCT, false, listOf("TRIANGLE", "BANDEAU", "UNDERWIRE", "BALCONETTE")),
                attribute("cup_thickness", "Cup thickness", AttributeScope.PRODUCT, false, listOf("NONE", "LIGHT", "PADDED")),
                attribute("shoulder_strap_design", "Shoulder strap design", AttributeScope.PRODUCT, false, listOf("HALTER", "ADJUSTABLE", "CROSS_BACK", "OFF_SHOULDER")),
                attribute("support_structure", "Support structure", AttributeScope.PRODUCT, false, listOf("NONE", "ELASTIC", "UNDERWIRE", "BONED")),
            ),
        ),
        TypeDefinition(
            "ONE_PIECE", "One Piece", "One-piece swimwear",
            listOf(
                attribute("support_level", "Support level", AttributeScope.PRODUCT, false, listOf("LIGHT", "MEDIUM", "HIGH")),
                attribute("coverage", "Coverage", AttributeScope.PRODUCT, false, listOf("MINIMAL", "MODERATE", "FULL")),
                attribute("torso_fit", "Torso fit", AttributeScope.PRODUCT, false, listOf("SHORT", "REGULAR", "LONG")),
                attribute("neckline", "Neckline", AttributeScope.PRODUCT, false, listOf("SCOOP", "V_NECK", "SQUARE", "HALTER", "HIGH_NECK")),
                attribute("back_style", "Back style", AttributeScope.PRODUCT, false, listOf("OPEN", "CROSS_BACK", "RACERBACK", "FULL_BACK")),
                booleanAttribute("tummy_control", "Tummy control"),
                booleanAttribute("removable_padding", "Removable padding"),
                attribute("cup_style", "Cup style", AttributeScope.PRODUCT, false, listOf("SOFT", "MOULDED", "UNDERWIRE")),
                attribute("cup_thickness", "Cup thickness", AttributeScope.PRODUCT, false, listOf("NONE", "LIGHT", "PADDED")),
                attribute("shoulder_strap_design", "Shoulder strap design", AttributeScope.PRODUCT, false, listOf("HALTER", "ADJUSTABLE", "CROSS_BACK", "OFF_SHOULDER")),
                attribute("support_structure", "Support structure", AttributeScope.PRODUCT, false, listOf("NONE", "ELASTIC", "UNDERWIRE", "BONED")),
            ),
        ),
        TypeDefinition(
            "DRESS", "Dress", "Dress apparel",
            listOf(
                attribute("length", "Length", AttributeScope.PRODUCT, false, listOf("MINI", "MIDI", "MAXI")),
                attribute("silhouette", "Silhouette", AttributeScope.PRODUCT, false, listOf("A_LINE", "SHEATH", "WRAP", "SLIP", "FIT_AND_FLARE")),
                attribute("neckline", "Neckline", AttributeScope.PRODUCT, false, listOf("SCOOP", "V_NECK", "SQUARE", "HALTER", "HIGH_NECK")),
                attribute("sleeve_type", "Sleeve type", AttributeScope.PRODUCT, false, listOf("SLEEVELESS", "SHORT", "LONG", "OFF_SHOULDER")),
                stringAttribute("fabric_description", "Fabric description"),
            ),
        ),
        TypeDefinition(
            "COVER_UP", "Cover Up", "Beach cover-up apparel",
            listOf(
                attribute("cover_up_style", "Cover-up style", AttributeScope.PRODUCT, false, listOf("KIMONO", "WRAP", "TUNIC", "DUSTER")),
                attribute("sheer_level", "Sheer level", AttributeScope.PRODUCT, false, listOf("SHEER", "SEMI_SHEER", "OPAQUE")),
                stringAttribute("fabric_description", "Fabric description"),
            ),
        ),
    )

    private val productMetadataCategories = listOf(
        CategoryDefinition("swimwear", "Swimwear", "Bikinis and one-piece swimwear"),
        CategoryDefinition("dresses", "Dresses", "Dress apparel"),
        CategoryDefinition("cover-ups", "Cover Ups", "Beach cover-up apparel"),
    )

    private fun attribute(code: String, name: String, scope: AttributeScope, required: Boolean, values: List<String>) =
        AttributeDefinition(code, name, scope, AttributeValueType.ENUM, required, values)

    private fun booleanAttribute(code: String, name: String) =
        AttributeDefinition(code, name, AttributeScope.PRODUCT, AttributeValueType.BOOLEAN, false, emptyList())

    private fun stringAttribute(code: String, name: String) =
        AttributeDefinition(code, name, AttributeScope.PRODUCT, AttributeValueType.STRING, false, emptyList())

    private data class TypeDefinition(val code: String, val name: String, val description: String, val attributes: List<AttributeDefinition>)
    private data class CategoryDefinition(val code: String, val name: String, val description: String)
    private data class AttributeDefinition(
        val code: String,
        val name: String,
        val scope: AttributeScope,
        val valueType: AttributeValueType,
        val required: Boolean,
        val allowedValues: List<String>,
    )

    private data class AdminDefinition(val username: String, val email: String, val firstName: String, val lastName: String)
    private data class CustomerProfile(
        val firstName: String,
        val lastName: String,
        val phone: String,
        val locale: String,
        val country: String,
        val stateOrProvince: String,
        val city: String,
        val postalCode: String,
        val street: String,
    )
    private data class TagDefinition(val name: String, val description: String, val color: String, val active: Boolean = true)
    private data class MaterialDefinition(val name: String, val percentage: BigDecimal)
    private data class CatalogDefinition(
        val skuCode: String,
        val typeCode: String,
        val categoryCode: String,
        val name: String,
        val basePrice: BigDecimal,
        val colors: List<String>,
        val materials: List<MaterialDefinition>,
        val attributes: List<Pair<String, String>>,
        val highlights: List<String>,
        val fitSense: String,
        val description: String,
        val designAndExtras: List<String>,
        val tagNames: List<String>,
        val imageKey: String,
        val status: Product.Status = Product.Status.ACTIVE,
        val deleted: Boolean = false,
    )
    private data class MockOrderDraft(
        val order: OrderEntity,
        val customer: User,
        val variant: ProductVariant,
        val quantity: Int,
        val createdAt: LocalDateTime,
    )
    private data class MockOrderRecord(
        val order: OrderEntity,
        val item: OrderItem,
        val customer: User,
        val createdAt: LocalDateTime,
    )
    private data class MockShipmentDraft(val shipment: Shipment, val order: MockOrderRecord)
    private data class MockTicketDraft(val ticket: SupportTicket, val createdAt: LocalDateTime)
    private data class TimestampRow(val id: Long, val createdAt: LocalDateTime, val updatedAt: LocalDateTime)

    private companion object {
        const val MOCK_CUSTOMER_SENTINEL = "mock_customer_001"
        const val MOCK_ORDER_COUNT = 60
        const val MOCK_TICKET_COUNT = 12
        val ZERO_MONEY: BigDecimal = BigDecimal("0.00")
        val FREE_SHIPPING_THRESHOLD: BigDecimal = BigDecimal("75.00")
        val BIKINI_SIZE_PAIRS = listOf("S" to "S", "M" to "S", "M" to "M", "L" to "M")
        val STANDARD_SIZES = listOf("S", "M", "L", "XL")
        val CARE_INSTRUCTIONS = listOf("Hand wash cold", "Do not bleach", "Line dry in shade")
        val PAID_ORDER_STATUSES = setOf(
            OrderStatus.PAID,
            OrderStatus.REFUNDING,
            OrderStatus.REFUNDED,
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
        )
        val SHIPPED_ORDER_STATUSES = setOf(OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED)
        val DELIVERED_ORDER_STATUSES = setOf(OrderStatus.DELIVERED, OrderStatus.COMPLETED)
        val SHIPMENT_ORDER_STATUSES = setOf(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED)
        val MOCK_ORDER_STATUSES = listOf(
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
            OrderStatus.CANCELLED,
            OrderStatus.REFUNDING,
            OrderStatus.REFUNDED,
        )
        val MOCK_ADMIN_DEFINITIONS = listOf(
            AdminDefinition("mock_admin", "mock.admin@shopmall.local", "Mock", "Administrator"),
            AdminDefinition("mock_operator", "mock.operator@shopmall.local", "Morgan", "Operator"),
        )
        val CUSTOMER_PROFILES = listOf(
            CustomerProfile("Avery", "Morgan", "+14155550101", "en-US", "US", "California", "San Francisco", "94105", "Market Street"),
            CustomerProfile("Sofia", "Martin", "+14155550102", "en-US", "US", "New York", "New York", "10001", "West 28th Street"),
            CustomerProfile("Mia", "Lopez", "+14155550103", "en-US", "US", "Florida", "Miami", "33131", "Brickell Avenue"),
            CustomerProfile("Harper", "Wilson", "+14155550104", "en-US", "US", "Texas", "Austin", "78701", "Congress Avenue"),
            CustomerProfile("Isla", "Brown", "+14155550105", "en-CA", "US", "Washington", "Seattle", "98101", "Pine Street"),
            CustomerProfile("Olivia", "Davis", "+14155550106", "en-US", "US", "Illinois", "Chicago", "60601", "Michigan Avenue"),
            CustomerProfile("Ella", "Thompson", "+14155550107", "en-US", "US", "Oregon", "Portland", "97205", "Burnside Street"),
            CustomerProfile("Grace", "Taylor", "+14155550108", "en-US", "US", "Massachusetts", "Boston", "02108", "Beacon Street"),
            CustomerProfile("Amelia", "Johnson", "+14155550109", "en-US", "US", "Colorado", "Denver", "80202", "Larimer Street"),
            CustomerProfile("Lily", "Anderson", "+14155550110", "en-US", "US", "Hawaii", "Honolulu", "96813", "King Street"),
            CustomerProfile("Chloe", "Lee", "+14155550111", "en-US", "US", "Arizona", "Phoenix", "85004", "Central Avenue"),
            CustomerProfile("Zoey", "Walker", "+14155550112", "en-US", "US", "Georgia", "Atlanta", "30303", "Peachtree Street"),
        )
        val TAG_DEFINITIONS = listOf(
            TagDefinition("New Arrival", "Recently introduced styles", "#0E7490"),
            TagDefinition("Best Seller", "Frequently purchased customer favorites", "#BE123C"),
            TagDefinition("Resort Ready", "Vacation and poolside essentials", "#2563EB"),
            TagDefinition("Inclusive Fit", "Designed with flexible fit options", "#15803D"),
            TagDefinition("Sculpting", "Structured support and smoothing features", "#9333EA"),
            TagDefinition("Linen Blend", "Breathable linen-blend apparel", "#A16207"),
            TagDefinition("Sheer Layer", "Lightweight cover-up layers", "#0891B2"),
            TagDefinition("Limited Color", "Seasonal colorway with limited stock", "#DB2777"),
            TagDefinition("Low Stock", "Operations tag for inventory review", "#DC2626"),
            TagDefinition("Archived Sample", "Inactive sample tag for administration testing", "#6B7280", active = false),
        )
        val CATALOG_DEFINITIONS = listOf(
            bikini("LAGOON", "Lagoon Triangle Bikini", "42.00", listOf("OCEAN_BLUE", "SEAFOAM"), "TRIANGLE", "NONE", "HALTER", "ELASTIC", listOf("New Arrival", "Resort Ready")),
            bikini("CORAL_RIB", "Coral Rib Bikini", "48.00", listOf("CORAL", "ROSE"), "TRIANGLE", "LIGHT", "ADJUSTABLE", "ELASTIC", listOf("Best Seller", "Inclusive Fit")),
            bikini("MIDNIGHT", "Midnight Bandeau Bikini", "55.00", listOf("BLACK", "MIDNIGHT_BLUE"), "BANDEAU", "PADDED", "OFF_SHOULDER", "BONED", listOf("Sculpting", "Best Seller")),
            bikini("PALM_TIE", "Palm Tie Bikini", "51.00", listOf("PALM_GREEN", "CITRUS"), "TRIANGLE", "LIGHT", "HALTER", "ELASTIC", listOf("Resort Ready", "Limited Color")),
            bikini("SEABREEZE", "Seabreeze Underwire Bikini", "63.00", listOf("COBALT", "IVORY"), "UNDERWIRE", "PADDED", "ADJUSTABLE", "UNDERWIRE", listOf("Sculpting", "Inclusive Fit")),
            bikini("SOLSTICE", "Solstice Balconette Bikini", "68.00", listOf("RUBY", "SAND"), "BALCONETTE", "PADDED", "CROSS_BACK", "UNDERWIRE", listOf("Low Stock", "Limited Color")),
            onePiece("COASTLINE", "Coastline Scoop One Piece", "69.00", listOf("NAVY", "SEA_BLUE"), "LIGHT", "MODERATE", "REGULAR", "SCOOP", "OPEN", false, true, "SOFT", "LIGHT", "ADJUSTABLE", "ELASTIC", listOf("Best Seller", "Resort Ready")),
            onePiece("SCULPT", "Sculpt Square Neck Suit", "78.00", listOf("RUBY", "BLACK"), "HIGH", "FULL", "REGULAR", "SQUARE", "FULL_BACK", true, false, "MOULDED", "PADDED", "ADJUSTABLE", "BONED", listOf("Sculpting", "Best Seller")),
            onePiece("HARBOR", "Harbor Cross Back Suit", "74.00", listOf("TEAL", "INDIGO"), "MEDIUM", "MODERATE", "LONG", "SCOOP", "CROSS_BACK", false, true, "SOFT", "LIGHT", "CROSS_BACK", "ELASTIC", listOf("Inclusive Fit", "Resort Ready")),
            onePiece("DUNE", "Dune Plunge Suit", "72.00", listOf("IVORY", "SAND"), "MEDIUM", "MINIMAL", "REGULAR", "V_NECK", "OPEN", false, false, "MOULDED", "LIGHT", "HALTER", "ELASTIC", listOf("Limited Color", "New Arrival")),
            onePiece("MARINA", "Marina High Neck Suit", "81.00", listOf("COBALT", "BLACK"), "HIGH", "FULL", "LONG", "HIGH_NECK", "RACERBACK", true, true, "MOULDED", "PADDED", "CROSS_BACK", "BONED", listOf("Sculpting", "Inclusive Fit")),
            onePiece("RIVIERA", "Riviera Halter One Piece", "76.00", listOf("TERRACOTTA", "OLIVE"), "MEDIUM", "MODERATE", "REGULAR", "HALTER", "OPEN", false, true, "SOFT", "LIGHT", "HALTER", "ELASTIC", listOf("Resort Ready"), status = Product.Status.INACTIVE),
            dress("SUNSET_LINEN", "Sunset Linen Maxi Dress", "86.00", listOf("TERRACOTTA", "OAT"), "MAXI", "A_LINE", "V_NECK", "SLEEVELESS", "Airy linen-viscose blend with a soft washed finish", listOf("Linen Blend", "Resort Ready")),
            dress("MARINA_WRAP", "Marina Wrap Midi Dress", "79.00", listOf("COBALT", "SKY"), "MIDI", "WRAP", "V_NECK", "SHORT", "Fluid woven fabric with a light drape", listOf("Best Seller", "New Arrival")),
            dress("BOARDWALK", "Boardwalk Mini Dress", "64.00", listOf("WHITE", "CORAL"), "MINI", "FIT_AND_FLARE", "SQUARE", "SLEEVELESS", "Textured cotton blend with breathable lining", listOf("Resort Ready", "Limited Color")),
            dress("GARDEN_SLIP", "Garden Slip Midi Dress", "82.00", listOf("SAGE", "ROSE"), "MIDI", "SLIP", "SCOOP", "SLEEVELESS", "Satin-touch recycled viscose with a bias cut", listOf("New Arrival", "Inclusive Fit")),
            dress("AZURE", "Azure Fit And Flare Dress", "89.00", listOf("AZURE", "NAVY"), "MIDI", "FIT_AND_FLARE", "SQUARE", "SHORT", "Structured stretch weave with a smooth hand feel", listOf("Best Seller", "Sculpting")),
            dress("MOONLIT", "Moonlit Sheath Maxi Dress", "96.00", listOf("BLACK", "PLUM"), "MAXI", "SHEATH", "HALTER", "SLEEVELESS", "Draped modal blend with a subtle luster", listOf("Archived Sample"), status = Product.Status.INACTIVE, deleted = true),
            coverUp("BREEZE_KIMONO", "Breeze Kimono Cover Up", "45.00", listOf("SKY", "WHITE"), "KIMONO", "SHEER", "Lightweight crinkle gauze with breathable texture", listOf("Sheer Layer", "Resort Ready")),
            coverUp("SEASIDE_WRAP", "Seaside Wrap Cover Up", "49.00", listOf("SAND", "CORAL"), "WRAP", "SEMI_SHEER", "Soft rayon voile with an adjustable wrap tie", listOf("Resort Ready"), status = Product.Status.INACTIVE),
            coverUp("COTTON_TUNIC", "Cotton Gauze Tunic", "53.00", listOf("MINT", "WHITE"), "TUNIC", "OPAQUE", "Double-gauze cotton with a relaxed breathable fit", listOf("Inclusive Fit", "New Arrival")),
            coverUp("LONGLINE", "Longline Beach Duster", "58.00", listOf("ROSE", "PLUM"), "DUSTER", "SEMI_SHEER", "Flowing recycled chiffon with a longline silhouette", listOf("Low Stock"), status = Product.Status.INACTIVE),
            coverUp("TIDEPOOL", "Tidepool Sheer Kimono", "47.00", listOf("AQUA", "LILAC"), "KIMONO", "SHEER", "Sheer mesh layer with quick-dry stretch", listOf("Sheer Layer", "Limited Color")),
            coverUp("SUNSET_WRAP", "Sunset Linen Wrap", "61.00", listOf("OAT", "TERRACOTTA"), "WRAP", "OPAQUE", "Textured linen blend with a natural hand feel", listOf("Linen Blend", "Archived Sample"), status = Product.Status.INACTIVE, deleted = true),
        )

        fun bikini(
            skuCode: String,
            name: String,
            price: String,
            colors: List<String>,
            cupStyle: String,
            cupThickness: String,
            strapDesign: String,
            supportStructure: String,
            tags: List<String>,
        ) = CatalogDefinition(
            skuCode = skuCode,
            typeCode = "BIKINI",
            categoryCode = "swimwear",
            name = name,
            basePrice = BigDecimal(price),
            colors = colors,
            materials = listOf(MaterialDefinition("Recycled nylon", BigDecimal("82.00")), MaterialDefinition("Elastane", BigDecimal("18.00"))),
            attributes = listOf(
                "cup_style" to cupStyle,
                "cup_thickness" to cupThickness,
                "shoulder_strap_design" to strapDesign,
                "support_structure" to supportStructure,
            ),
            highlights = listOf("Quick-dry recycled fabric", "Fully lined for confident coverage", "Adjustable fit details"),
            fitSense = "Flexible fit with adjustable straps; choose separate top and bottom sizes.",
            description = "$name pairs supportive swim construction with a clean, versatile beach-ready silhouette.",
            designAndExtras = listOf("Fully lined construction", "Colorfast fabric", "Adjustable straps"),
            tagNames = tags,
            imageKey = skuCode.lowercase(),
        )

        fun onePiece(
            skuCode: String,
            name: String,
            price: String,
            colors: List<String>,
            supportLevel: String,
            coverage: String,
            torsoFit: String,
            neckline: String,
            backStyle: String,
            tummyControl: Boolean,
            removablePadding: Boolean,
            cupStyle: String,
            cupThickness: String,
            strapDesign: String,
            supportStructure: String,
            tags: List<String>,
            status: Product.Status = Product.Status.ACTIVE,
        ) = CatalogDefinition(
            skuCode = skuCode,
            typeCode = "ONE_PIECE",
            categoryCode = "swimwear",
            name = name,
            basePrice = BigDecimal(price),
            colors = colors,
            materials = listOf(MaterialDefinition("Polyamide", BigDecimal("75.00")), MaterialDefinition("Elastane", BigDecimal("25.00"))),
            attributes = listOf(
                "support_level" to supportLevel,
                "coverage" to coverage,
                "torso_fit" to torsoFit,
                "neckline" to neckline,
                "back_style" to backStyle,
                "tummy_control" to tummyControl.toString(),
                "removable_padding" to removablePadding.toString(),
                "cup_style" to cupStyle,
                "cup_thickness" to cupThickness,
                "shoulder_strap_design" to strapDesign,
                "support_structure" to supportStructure,
            ),
            highlights = listOf("Supportive one-piece construction", "Chlorine-resistant stretch fabric", "Smooth, stay-put fit"),
            fitSense = "True to size with stretch recovery designed for swimming and all-day wear.",
            description = "$name is built for reliable support, comfortable movement, and effortless pool-to-beach styling.",
            designAndExtras = listOf("Fully lined body", "Four-way stretch", "Quick-dry finish"),
            tagNames = tags,
            imageKey = skuCode.lowercase(),
            status = status,
        )

        fun dress(
            skuCode: String,
            name: String,
            price: String,
            colors: List<String>,
            length: String,
            silhouette: String,
            neckline: String,
            sleeveType: String,
            fabricDescription: String,
            tags: List<String>,
            status: Product.Status = Product.Status.ACTIVE,
            deleted: Boolean = false,
        ) = CatalogDefinition(
            skuCode = skuCode,
            typeCode = "DRESS",
            categoryCode = "dresses",
            name = name,
            basePrice = BigDecimal(price),
            colors = colors,
            materials = listOf(MaterialDefinition("Viscose", BigDecimal("55.00")), MaterialDefinition("Linen", BigDecimal("45.00"))),
            attributes = listOf(
                "length" to length,
                "silhouette" to silhouette,
                "neckline" to neckline,
                "sleeve_type" to sleeveType,
                "fabric_description" to fabricDescription,
            ),
            highlights = listOf("Breathable woven fabric", "Easy day-to-evening styling", "Comfortable unlined drape"),
            fitSense = "Relaxed through the body; refer to the size guide for length and bust measurements.",
            description = "$name is a lightweight warm-weather dress designed for resort days, dinners, and travel.",
            designAndExtras = listOf("Soft woven lining", "Finished interior seams", "Packable lightweight fabric"),
            tagNames = tags,
            imageKey = skuCode.lowercase(),
            status = status,
            deleted = deleted,
        )

        fun coverUp(
            skuCode: String,
            name: String,
            price: String,
            colors: List<String>,
            style: String,
            sheerLevel: String,
            fabricDescription: String,
            tags: List<String>,
            status: Product.Status = Product.Status.ACTIVE,
            deleted: Boolean = false,
        ) = CatalogDefinition(
            skuCode = skuCode,
            typeCode = "COVER_UP",
            categoryCode = "cover-ups",
            name = name,
            basePrice = BigDecimal(price),
            colors = colors,
            materials = listOf(MaterialDefinition("Cotton", BigDecimal("70.00")), MaterialDefinition("Rayon", BigDecimal("30.00"))),
            attributes = listOf(
                "cover_up_style" to style,
                "sheer_level" to sheerLevel,
                "fabric_description" to fabricDescription,
            ),
            highlights = listOf("Lightweight layering piece", "Easy packable construction", "Designed over swimwear"),
            fitSense = "Relaxed layer intended to drape comfortably over swimwear.",
            description = "$name provides breathable coverage for beach walks, poolside lounging, and resort travel.",
            designAndExtras = listOf("Lightweight fabric", "Quick-dry construction", "Soft finished edges"),
            tagNames = tags,
            imageKey = skuCode.lowercase(),
            status = status,
            deleted = deleted,
        )
    }
}
