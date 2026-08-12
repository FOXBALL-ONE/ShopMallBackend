package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationItem
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductImage
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.repository.HomeRecommendationPlanRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.impl.HomeRecommendationServiceImpl
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeRecommendationServiceImplTest {
    private val planRepository = mock(HomeRecommendationPlanRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val currentInstant = Instant.parse("2026-08-12T04:00:00Z")
    private val homeRecommendationCache = HomeRecommendationCache()
    private val service = HomeRecommendationServiceImpl(
        planRepository,
        productRepository,
        homeRecommendationCache,
        Clock.fixed(currentInstant, ZoneOffset.UTC),
        "UTC",
        60,
    )

    @Test
    fun `missing published plan resolves the system default tabs`() {
        val products = (1L..4L).map { sellableProduct(it) }
        `when`(
            planRepository.findCurrent(
                HomeRecommendationPlan.Channel.CUSTOMER_WEB,
                HomeRecommendationPlan.Status.PUBLISHED,
                java.time.LocalDateTime.of(2026, 8, 12, 4, 0),
                PageRequest.of(0, 1),
            ),
        ).thenReturn(emptyList())
        `when`(
            productRepository.findNewArrivalRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                1,
                null,
                null,
                null,
                java.time.LocalDateTime.of(2026, 7, 13, 4, 0),
                PageRequest.of(0, 32),
            ),
        ).thenReturn(products)
        `when`(
            productRepository.findBestSellerRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                1,
                null,
                null,
                null,
                PageRequest.of(0, 32),
            ),
        ).thenReturn(products)

        val resolved = service.current(productLimitPerGroup = 4)

        assertTrue(resolved.fallback)
        assertEquals(null, resolved.planId)
        assertEquals(java.time.LocalDateTime.of(2026, 8, 12, 4, 0), resolved.generatedAt)
        assertEquals(HomeRecommendationSection.DisplayStyle.TABS, resolved.sections.single().displayStyle)
        assertEquals(listOf("new_arrivals", "best_sellers"), resolved.sections.single().groups.map { it.code })
        assertEquals(4, resolved.sections.single().groups.first().products.size)
    }

    @Test
    fun `current caches the immutable resolved response for the configured ttl`() {
        val product = sellableProduct(1)
        val plan = recommendationPlan(automaticGroup("featured")).apply {
            status = HomeRecommendationPlan.Status.PUBLISHED
            effectiveFrom = java.time.LocalDateTime.of(2026, 8, 11, 4, 0)
        }
        `when`(
            planRepository.findCurrent(
                HomeRecommendationPlan.Channel.CUSTOMER_WEB,
                HomeRecommendationPlan.Status.PUBLISHED,
                java.time.LocalDateTime.of(2026, 8, 12, 4, 0),
                PageRequest.of(0, 1),
            ),
        ).thenReturn(listOf(plan))
        `when`(
            productRepository.findNewArrivalRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                1,
                null,
                null,
                null,
                java.time.LocalDateTime.of(2026, 7, 13, 4, 0),
                PageRequest.of(0, 32),
            ),
        ).thenReturn(listOf(product))

        val first = service.current()
        product.name = "Changed after resolution"
        val second = service.current()

        assertEquals(first.requestId, second.requestId)
        assertEquals("Product 1", second.sections.single().groups.single().products.single().product.name)
        verify(planRepository, times(1)).findCurrent(
            HomeRecommendationPlan.Channel.CUSTOMER_WEB,
            HomeRecommendationPlan.Status.PUBLISHED,
            java.time.LocalDateTime.of(2026, 8, 12, 4, 0),
            PageRequest.of(0, 1),
        )
        verify(productRepository, times(1)).findNewArrivalRecommendationCandidates(
            Product.Status.ACTIVE,
            ProductVariant.Status.ACTIVE,
            1,
            null,
            null,
            null,
            java.time.LocalDateTime.of(2026, 7, 13, 4, 0),
            PageRequest.of(0, 32),
        )
    }

    @Test
    fun `hybrid selection keeps pinned manual order skips invalid products and backfills automatically`() {
        val regular = sellableProduct(1)
        val pinned = sellableProduct(2)
        val automatic = sellableProduct(3)
        val invalid = sellableProduct(4, stock = 2)
        val group = HomeRecommendationGroup(
            code = "featured",
            selectionMode = HomeRecommendationGroup.SelectionMode.HYBRID,
            strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
            itemLimit = 3,
            minimumStock = 5,
            lookbackDays = 30,
            fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.NONE,
        ).apply {
            replaceItems(
                listOf(
                    HomeRecommendationItem(productId = 1, pinned = false, sortOrder = 0),
                    HomeRecommendationItem(productId = 2, pinned = true, customBadge = "STAFF PICK", sortOrder = 2),
                    HomeRecommendationItem(productId = 4, pinned = true, sortOrder = 3),
                ),
            )
        }
        val plan = recommendationPlan(group)
        `when`(productRepository.findRecommendationProductsByIdIn(listOf(1L, 2L, 4L)))
            .thenReturn(listOf(regular, pinned, invalid))
        `when`(
            productRepository.findNewArrivalRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                5,
                null,
                null,
                null,
                java.time.LocalDateTime.of(2026, 7, 13, 4, 0),
                PageRequest.of(0, 32),
            ),
        ).thenReturn(listOf(automatic))

        val resolved = service.resolve(plan, productLimitPerGroup = 3)
        val resolvedGroup = resolved.sections.single().groups.single()

        assertEquals(5, resolvedGroup.minimumStock)
        assertEquals(listOf(2L, 1L, 3L), resolvedGroup.products.map { it.product.id })
        assertEquals("STAFF PICK", resolvedGroup.products.first().badge)
        assertEquals(listOf(1, 2, 3), resolvedGroup.products.map { it.context.position })
    }

    @Test
    fun `resolved product snapshot contains only sellable persistent variants`() {
        val product = sellableProduct(1)
        product.addVariant(
            ProductVariant(
                id = 11,
                sku = "SKU-INACTIVE",
                color = "Black",
                price = BigDecimal("29.99"),
                warehouseVolume = 10,
                status = ProductVariant.Status.INACTIVE,
                optionSignature = "inactive=true",
            ),
        )
        product.addVariant(
            ProductVariant(
                id = 12,
                sku = "SKU-LOW-STOCK",
                color = "Black",
                price = BigDecimal("29.99"),
                warehouseVolume = 0,
                status = ProductVariant.Status.ACTIVE,
                optionSignature = "stock=0",
            ),
        )
        product.addVariant(
            ProductVariant(
                id = 13,
                sku = "SKU-ZERO-PRICE",
                color = "Black",
                price = BigDecimal.ZERO,
                warehouseVolume = 10,
                status = ProductVariant.Status.ACTIVE,
                optionSignature = "price=0",
            ),
        )
        product.addVariant(
            ProductVariant(
                sku = "SKU-NOT-PERSISTED",
                color = "Black",
                price = BigDecimal("29.99"),
                warehouseVolume = 10,
                status = ProductVariant.Status.ACTIVE,
                optionSignature = "persistent=false",
            ),
        )
        `when`(
            productRepository.findNewArrivalRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                1,
                null,
                null,
                null,
                java.time.LocalDateTime.of(2026, 7, 13, 4, 0),
                PageRequest.of(0, 32),
            ),
        ).thenReturn(listOf(product))

        val resolved = service.resolve(recommendationPlan(automaticGroup("featured")))
        val variants = resolved.sections.single().groups.single().products.single().product.variants

        assertEquals(listOf(10L), variants.map { it.id })
    }

    @Test
    fun `deduplication removes products already used by an earlier section`() {
        val products = listOf(sellableProduct(1), sellableProduct(2), sellableProduct(3))
        val firstGroup = automaticGroup("first")
        val secondGroup = automaticGroup("second")
        val firstSection = recommendationSection("first_section", firstGroup, sortOrder = 0)
        val secondSection = recommendationSection("second_section", secondGroup, sortOrder = 1)
        val plan = HomeRecommendationPlan(
            id = 8,
            name = "Deduplicated",
            fallbackEnabled = false,
            deduplicateAcrossSections = true,
        ).apply { replaceSections(listOf(firstSection, secondSection)) }
        `when`(
            productRepository.findNewArrivalRecommendationCandidates(
                Product.Status.ACTIVE,
                ProductVariant.Status.ACTIVE,
                1,
                null,
                null,
                null,
                java.time.LocalDateTime.of(2026, 7, 13, 4, 0),
                PageRequest.of(0, 32),
            ),
        ).thenReturn(products)

        val resolved = service.resolve(plan, productLimitPerGroup = 2)

        assertEquals(listOf(1L, 2L), resolved.sections[0].groups.single().products.map { it.product.id })
        assertEquals(listOf(3L), resolved.sections[1].groups.single().products.map { it.product.id })
    }

    private fun recommendationPlan(group: HomeRecommendationGroup) = HomeRecommendationPlan(
        id = 7,
        name = "Homepage",
        fallbackEnabled = false,
        deduplicateAcrossSections = true,
    ).apply { replaceSections(listOf(recommendationSection("featured_section", group))) }

    private fun recommendationSection(
        code: String,
        group: HomeRecommendationGroup,
        sortOrder: Int = 0,
    ) = HomeRecommendationSection(
        code = code,
        title = code,
        displayStyle = HomeRecommendationSection.DisplayStyle.GRID,
        itemLimit = 8,
        sortOrder = sortOrder,
    ).apply { replaceGroups(listOf(group)) }

    private fun automaticGroup(code: String) = HomeRecommendationGroup(
        code = code,
        selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
        strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
        itemLimit = 2,
        minimumStock = 1,
        lookbackDays = 30,
        fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.NONE,
    )

    private fun sellableProduct(id: Long, stock: Int = 10) = Product(
        id = id,
        productType = ProductType(id = 1, code = "SWIMWEAR", name = "Swimwear"),
        name = "Product $id",
        status = Product.Status.ACTIVE,
        images = mutableListOf(ProductImage(url = "https://example.com/$id.jpg", primary = true)),
    ).apply {
        addVariant(
            ProductVariant(
                id = id * 10,
                sku = "SKU-$id",
                color = "Black",
                price = BigDecimal("29.99"),
                warehouseVolume = stock,
                salesVolume = id,
                status = ProductVariant.Status.ACTIVE,
                optionSignature = "color=black",
            ),
        )
    }
}
