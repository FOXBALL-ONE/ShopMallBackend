package top.foxball.shopmall.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.HomeRecommendationCategory
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.HomeRecommendationPlanRepository
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.HomeRecommendationCache
import top.foxball.shopmall.service.HomeRecommendationService
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Service
@Transactional(readOnly = true)
class HomeRecommendationServiceImpl(
    private val planRepository: HomeRecommendationPlanRepository,
    private val productRepository: ProductRepository,
    private val productCategoryRepository: ProductCategoryRepository,
    private val homeRecommendationCache: HomeRecommendationCache,
    private val clock: Clock,
    @Value("\${shopmall.home-recommendation.time-zone:Asia/Shanghai}") recommendationTimeZone: String,
    @Value("\${shopmall.home-recommendation.cache-ttl-seconds:60}") private val cacheTtlSeconds: Long,
) : HomeRecommendationService {
    private val recommendationZoneId = ZoneId.of(recommendationTimeZone)

    override fun current(sectionLimit: Int, productLimitPerGroup: Int?): HomeRecommendationService.ResolvedPlan {
        if (sectionLimit !in 1..20) throw ParamErrorException("section_limit 必须在 1 到 20 之间")
        if (productLimitPerGroup != null && productLimitPerGroup !in 1..24) {
            throw ParamErrorException("product_limit_per_group 必须在 1 到 24 之间")
        }
        val currentTime = now()
        homeRecommendationCache.getCurrent(
            HomeRecommendationPlan.Channel.CUSTOMER_WEB,
            sectionLimit,
            productLimitPerGroup,
            currentTime,
        )?.let { return it }
        val plan = planRepository.findCurrent(
            HomeRecommendationPlan.Channel.CUSTOMER_WEB,
            HomeRecommendationPlan.Status.PUBLISHED,
            currentTime,
            PageRequest.of(0, 1),
        ).firstOrNull()
        val resolved = if (plan == null) {
            resolveDefault(sectionLimit, productLimitPerGroup, currentTime)
        } else {
            resolvePlan(plan, sectionLimit, productLimitPerGroup, currentTime, fallback = false)
        }
        homeRecommendationCache.putCurrent(
            HomeRecommendationPlan.Channel.CUSTOMER_WEB,
            sectionLimit,
            productLimitPerGroup,
            resolved,
        )
        return resolved
    }

    override fun resolve(
        plan: HomeRecommendationPlan,
        sectionLimit: Int,
        productLimitPerGroup: Int?,
        useDefaultFallback: Boolean,
    ): HomeRecommendationService.ResolvedPlan {
        if (sectionLimit !in 1..20) throw ParamErrorException("section_limit 必须在 1 到 20 之间")
        if (productLimitPerGroup != null && productLimitPerGroup !in 1..24) {
            throw ParamErrorException("product_limit_per_group 必须在 1 到 24 之间")
        }
        return resolvePlan(
            plan,
            sectionLimit,
            productLimitPerGroup,
            now(),
            fallback = false,
            useDefaultFallback = useDefaultFallback,
        )
    }

    private fun resolvePlan(
        plan: HomeRecommendationPlan,
        sectionLimit: Int,
        productLimitPerGroup: Int?,
        currentTime: LocalDateTime,
        fallback: Boolean,
        useDefaultFallback: Boolean = true,
    ): HomeRecommendationService.ResolvedPlan {
        initializePlan(plan)
        val requestId = "rec_${UUID.randomUUID().toString().replace("-", "")}"
        val manualIds = plan.sections.flatMap { section -> section.groups.flatMap { group -> group.items.map { it.productId } } }.distinct()
        val manualProducts = if (manualIds.isEmpty()) emptyMap() else {
            productRepository.findRecommendationProductsByIdIn(manualIds)
                .onEach(::initializeProduct)
                .associateBy { requireNotNull(it.id) }
        }
        val globallySeen = linkedSetOf<Long>()
        val configuredCategoryIds = plan.categories.map { it.categoryId }.distinct()
        val configuredCategories = if (configuredCategoryIds.isEmpty()) emptyMap() else {
            productCategoryRepository.findAllById(configuredCategoryIds)
                .filter { it.status == ProductCategory.Status.ACTIVE && it.parent == null }
                .associateBy { requireNotNull(it.id) }
        }
        val categories = plan.categories.sortedWith(
            compareBy(HomeRecommendationCategory::sortOrder).thenBy(HomeRecommendationCategory::id),
        ).mapNotNull { recommendationCategory ->
            val category = configuredCategories[recommendationCategory.categoryId] ?: return@mapNotNull null
            HomeRecommendationService.ResolvedCategory(
                id = recommendationCategory.id,
                categoryId = recommendationCategory.categoryId,
                code = category.code,
                name = category.name,
                imageUrl = recommendationCategory.imageUrl,
                altText = recommendationCategory.altText,
            )
        }
        val sections = mutableListOf<HomeRecommendationService.ResolvedSection>()
        plan.sections.sortedWith(compareBy(HomeRecommendationSection::sortOrder, HomeRecommendationSection::id))
            .take(sectionLimit)
            .forEach { section ->
                val seenBeforeSection = globallySeen.toSet()
                val seenInSection = linkedSetOf<Long>()
                val groups = section.groups.sortedWith(compareBy(HomeRecommendationGroup::sortOrder, HomeRecommendationGroup::id))
                    .mapNotNull { group ->
                        val configuredLimit = minOf(group.itemLimit, section.itemLimit, productLimitPerGroup ?: 24)
                        val resolved = resolveGroup(
                            planId = plan.id,
                            requestId = requestId,
                            section = section,
                            group = group,
                            limit = configuredLimit,
                            excludedIds = if (plan.deduplicateAcrossSections) seenBeforeSection else emptySet(),
                            manualProducts = manualProducts,
                            currentTime = currentTime,
                        )
                        seenInSection += resolved.products.map { it.product.id }
                        resolved.takeIf { it.products.isNotEmpty() }
                    }
                globallySeen += seenInSection
                if (groups.isNotEmpty() || !section.hideWhenEmpty) {
                    sections += HomeRecommendationService.ResolvedSection(
                        id = section.id,
                        code = section.code,
                        eyebrow = section.eyebrow,
                        title = section.title,
                        subtitle = section.subtitle,
                        displayStyle = section.displayStyle,
                        desktopColumns = section.desktopColumns,
                        mobileColumns = section.mobileColumns,
                        linkLabel = section.linkLabel,
                        linkUrl = section.linkUrl,
                        groups = groups,
                    )
                }
        }
        if (sections.isEmpty() && plan.fallbackEnabled && !fallback && useDefaultFallback) {
            return resolveDefault(sectionLimit, productLimitPerGroup, currentTime).copy(
                categoriesConfigured = plan.categories.isNotEmpty(),
                categories = categories,
            )
        }
        return HomeRecommendationService.ResolvedPlan(
            planId = plan.id,
            planVersion = plan.version,
            requestId = requestId,
            generatedAt = currentTime,
            expiresAt = currentTime.plusSeconds(cacheTtlSeconds.coerceAtLeast(1)),
            categoriesConfigured = plan.categories.isNotEmpty(),
            categories = categories,
            sections = sections,
            fallback = fallback,
        )
    }

    private fun resolveDefault(
        sectionLimit: Int,
        productLimitPerGroup: Int?,
        currentTime: LocalDateTime,
    ): HomeRecommendationService.ResolvedPlan {
        val plan = HomeRecommendationPlan(
            name = "System default homepage recommendations",
            status = HomeRecommendationPlan.Status.PUBLISHED,
            effectiveFrom = currentTime,
            fallbackEnabled = false,
            deduplicateAcrossSections = true,
        )
        val section = HomeRecommendationSection(
            code = "whats_hot",
            eyebrow = "TRENDING NOW",
            title = "What's hot right now",
            subtitle = "Discover the latest arrivals and customer favorites.",
            displayStyle = HomeRecommendationSection.DisplayStyle.TABS,
            desktopColumns = 4,
            mobileColumns = 2,
            linkLabel = "Shop all",
            linkUrl = "/collections/shop",
            itemLimit = 8,
            hideWhenEmpty = true,
        )
        section.replaceGroups(
            listOf(
                HomeRecommendationGroup(
                    code = "new_arrivals",
                    title = "New Arrivals",
                    selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                    strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
                    itemLimit = 8,
                    lookbackDays = 30,
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
                    lookbackDays = null,
                    minimumStock = 1,
                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.LATEST,
                    sortOrder = 1,
                ),
            ),
        )
        plan.replaceSections(listOf(section))
        return resolvePlan(plan, sectionLimit, productLimitPerGroup, currentTime, fallback = true)
    }

    private fun resolveGroup(
        planId: Long?,
        requestId: String,
        section: HomeRecommendationSection,
        group: HomeRecommendationGroup,
        limit: Int,
        excludedIds: Set<Long>,
        manualProducts: Map<Long, Product>,
        currentTime: LocalDateTime,
    ): HomeRecommendationService.ResolvedGroup {
        val selected = linkedMapOf<Long, Pair<Product, String?>>()
        if (group.selectionMode != HomeRecommendationGroup.SelectionMode.AUTO) {
            group.items.sortedWith(
                compareByDescending<top.foxball.shopmall.entity.jdbc.HomeRecommendationItem> { it.pinned }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id },
            ).forEach { item ->
                val product = manualProducts[item.productId]
                if (
                    product != null && item.productId !in excludedIds && item.productId !in selected &&
                    eligible(product, group.minimumStock, requireImage = false)
                ) {
                    selected[item.productId] = product to item.customBadge
                }
            }
        }
        if (group.selectionMode != HomeRecommendationGroup.SelectionMode.MANUAL && selected.size < limit) {
            candidateProducts(group.strategy, group, currentTime, candidateLimit(limit)).forEach { product ->
                val productId = requireNotNull(product.id)
                if (
                    productId !in excludedIds && productId !in selected && eligible(product, group.minimumStock)
                ) {
                    selected[productId] = product to null
                }
            }
        }
        if (selected.size < limit) {
            val fallbackStrategy = when (group.fallbackStrategy) {
                HomeRecommendationGroup.FallbackStrategy.NONE -> null
                HomeRecommendationGroup.FallbackStrategy.LATEST -> HomeRecommendationGroup.Strategy.NEW_ARRIVALS
                HomeRecommendationGroup.FallbackStrategy.BEST_SELLERS -> HomeRecommendationGroup.Strategy.BEST_SELLERS
            }
            if (fallbackStrategy != null) {
                val fallbackGroup = HomeRecommendationGroup(
                    strategy = fallbackStrategy,
                    minimumStock = group.minimumStock,
                    categoryId = group.categoryId,
                    productType = group.productType,
                    tagId = group.tagId,
                    lookbackDays = null,
                )
                candidateProducts(fallbackStrategy, fallbackGroup, currentTime, candidateLimit(limit)).forEach { product ->
                    val productId = requireNotNull(product.id)
                    if (
                        productId !in excludedIds && productId !in selected && eligible(product, group.minimumStock)
                    ) {
                        selected[productId] = product to null
                    }
                }
            }
        }
        val products = selected.values.take(limit).mapIndexed { index, (product, customBadge) ->
            HomeRecommendationService.ResolvedProduct(
                product = HomeRecommendationService.ProductData(
                    id = requireNotNull(product.id),
                    productType = requireNotNull(product.productType).code,
                    categoryId = product.category?.id,
                    name = product.name,
                    status = product.status,
                    highlights = product.highlights.toList(),
                    materials = product.materials.map {
                        HomeRecommendationService.ProductMaterialData(it.name, it.percentage)
                    },
                    images = product.images.mapIndexed { imageIndex, image ->
                        HomeRecommendationService.ProductImageData(
                            url = image.url,
                            altText = image.altText,
                            primary = image.primary,
                            sortOrder = imageIndex,
                        )
                    },
                    attributes = product.attributes.map {
                        HomeRecommendationService.ProductAttributeData(it.code, it.value)
                    },
                    fitSense = product.fitSense,
                    description = product.description,
                    designAndExtras = product.designAndExtras.toList(),
                    careInstructions = product.careInstructions.map { it.text },
                    tags = product.tags.map { it.name }.sorted(),
                    score = product.score,
                    variants = product.variants
                        .filter { variant ->
                            variant.id != null && variant.status == ProductVariant.Status.ACTIVE &&
                                variant.warehouseVolume >= group.minimumStock && variant.price.signum() > 0
                        }
                        .sortedWith(compareBy(ProductVariant::displayOrder, ProductVariant::id))
                        .map { variant ->
                            HomeRecommendationService.ProductVariantData(
                                id = requireNotNull(variant.id),
                                sku = variant.sku,
                                size = variant.size,
                                color = variant.color,
                                price = variant.price,
                                warehouseVolume = variant.warehouseVolume,
                                salesVolume = variant.salesVolume,
                                displayOrder = variant.displayOrder,
                                status = variant.status,
                                attributes = variant.attributes.map {
                                    HomeRecommendationService.ProductAttributeData(it.code, it.value)
                                },
                            )
                        },
                    createdAt = product.createdAt,
                    updatedAt = product.updatedAt,
                ),
                badge = customBadge ?: when (group.strategy) {
                    HomeRecommendationGroup.Strategy.NEW_ARRIVALS -> "NEW"
                    HomeRecommendationGroup.Strategy.BEST_SELLERS -> "BEST SELLER"
                    HomeRecommendationGroup.Strategy.HIGH_RATED -> "TOP RATED"
                    HomeRecommendationGroup.Strategy.EDITOR_PICKS -> null
                },
                context = HomeRecommendationService.RecommendationContext(
                    requestId = requestId,
                    planId = planId,
                    sectionCode = section.code,
                    groupCode = group.code,
                    strategy = group.strategy,
                    position = index + 1,
                ),
            )
        }
        return HomeRecommendationService.ResolvedGroup(
            id = group.id,
            code = group.code,
            title = group.title,
            selectionMode = group.selectionMode,
            strategy = group.strategy,
            minimumStock = group.minimumStock,
            products = products,
        )
    }

    private fun candidateProducts(
        strategy: HomeRecommendationGroup.Strategy,
        group: HomeRecommendationGroup,
        currentTime: LocalDateTime,
        limit: Int,
    ): List<Product> {
        val pageable = PageRequest.of(0, limit.coerceIn(1, 100))
        val productType = group.productType?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val products = when (strategy) {
            HomeRecommendationGroup.Strategy.NEW_ARRIVALS -> {
                if (group.lookbackDays == null) {
                    productRepository.findLatestRecommendationCandidates(
                        Product.Status.ACTIVE,
                        ProductVariant.Status.ACTIVE,
                        group.minimumStock,
                        group.categoryId,
                        productType,
                        group.tagId,
                        pageable,
                    )
                } else {
                    productRepository.findNewArrivalRecommendationCandidates(
                        Product.Status.ACTIVE,
                        ProductVariant.Status.ACTIVE,
                        group.minimumStock,
                        group.categoryId,
                        productType,
                        group.tagId,
                        currentTime.minusDays(group.lookbackDays!!.toLong()),
                        pageable,
                    )
                }
            }
            HomeRecommendationGroup.Strategy.BEST_SELLERS -> productRepository.findBestSellerRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                group.minimumStock,
                group.categoryId,
                productType,
                group.tagId,
                pageable,
            )
            HomeRecommendationGroup.Strategy.HIGH_RATED -> productRepository.findHighRatedRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                group.minimumStock,
                group.categoryId,
                productType,
                group.tagId,
                pageable,
            )
            HomeRecommendationGroup.Strategy.EDITOR_PICKS -> emptyList()
        }
        products.forEach(::initializeProduct)
        return products
    }

    private fun eligible(product: Product, minimumStock: Int, requireImage: Boolean = true): Boolean =
        product.id != null && product.productType != null && product.status == Product.Status.ACTIVE &&
            product.deletedAt == null && (!requireImage || product.images.isNotEmpty()) && product.variants.any {
                it.id != null && it.status == ProductVariant.Status.ACTIVE &&
                    it.warehouseVolume >= minimumStock && it.price.signum() > 0
            }

    private fun initializePlan(plan: HomeRecommendationPlan) {
        plan.categories.size
        plan.sections.forEach { section ->
            section.groups.forEach { group -> group.items.size }
        }
    }

    private fun initializeProduct(product: Product) {
        product.productType?.code
        product.category?.id
        product.images.size
        product.tags.size
        product.attributes.size
        product.variants.forEach { it.attributes.size }
    }

    private fun candidateLimit(limit: Int): Int = maxOf(limit * 4, 32).coerceAtMost(100)

    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), recommendationZoneId)
}
