package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationItem
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.handler.HomeRecommendationScheduleConflictException
import top.foxball.shopmall.handler.HomeRecommendationVersionConflictException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.HomeRecommendationPlanRepository
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.impl.AdminHomeRecommendationServiceImpl
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminHomeRecommendationServiceImplTest {
    private val planRepository = mock(HomeRecommendationPlanRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val productCategoryRepository = mock(ProductCategoryRepository::class.java)
    private val productTypeRepository = mock(ProductTypeRepository::class.java)
    private val tagRepository = mock(TagRepository::class.java)
    private val adminAccessService = mock(AdminAccessService::class.java)
    private val homeRecommendationService = mock(HomeRecommendationService::class.java)
    private val homeRecommendationCache = HomeRecommendationCache()
    private val currentTime = LocalDateTime.of(2026, 8, 12, 12, 0)
    private val service = AdminHomeRecommendationServiceImpl(
        planRepository,
        productRepository,
        productCategoryRepository,
        productTypeRepository,
        tagRepository,
        adminAccessService,
        homeRecommendationService,
        homeRecommendationCache,
        Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC),
        "UTC",
    )

    @Test
    fun `create stores ordered homepage categories with normalized image metadata`() {
        val category = ProductCategory(id = 2, code = "swimwear", name = "Swimwear")
        `when`(productCategoryRepository.findAllById(setOf(2L))).thenReturn(listOf(category))
        `when`(planRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(HomeRecommendationPlan::class.java)))
            .thenAnswer { invocation -> invocation.arguments[0] as HomeRecommendationPlan }

        val created = service.create(
            99,
            createCommand(
                categories = listOf(
                    AdminHomeRecommendationService.CategoryCommand(
                        categoryId = 2,
                        imageUrl = " http://localhost:8080/api/product-images/file-id?signature=test ",
                        altText = " Swimwear collection ",
                        sortOrder = 0,
                    ),
                ),
                sections = listOf(sectionCommand()),
            ),
        )

        assertEquals(listOf(2L), created.categories.map { it.categoryId })
        assertEquals("http://localhost:8080/api/product-images/file-id?signature=test", created.categories.single().imageUrl)
        assertEquals("Swimwear collection", created.categories.single().altText)
    }

    @Test
    fun `homepage categories reject inactive or nested catalog categories`() {
        val inactive = ProductCategory(
            id = 2,
            code = "inactive",
            name = "Inactive",
            status = ProductCategory.Status.INACTIVE,
        )
        `when`(productCategoryRepository.findAllById(setOf(2L))).thenReturn(listOf(inactive))

        val error = assertFailsWith<ParamErrorException> {
            service.create(
                99,
                createCommand(
                    categories = listOf(
                        AdminHomeRecommendationService.CategoryCommand(2, "/images/inactive.jpg", null, 0),
                    ),
                    sections = listOf(sectionCommand()),
                ),
            )
        }

        assertEquals("首页展示分类必须是启用中的顶级分类：Inactive", error.message)
    }

    @Test
    fun `stale update version reports the current version before validation`() {
        val plan = plan(id = 7, version = 4)
        `when`(planRepository.findByIdForUpdate(7)).thenReturn(plan)

        val error = assertFailsWith<HomeRecommendationVersionConflictException> {
            service.update(99, 7, updateCommand(expectedVersion = 3))
        }

        assertEquals(4, error.actualVersion)
        verify(planRepository, never()).saveAndFlush(plan)
    }

    @Test
    fun `tabs section requires at least two configured groups`() {
        val command = createCommand(
            sections = listOf(sectionCommand(displayStyle = HomeRecommendationSection.DisplayStyle.TABS)),
        )

        val error = assertFailsWith<ParamErrorException> { service.create(99, command) }

        assertEquals("第 1 个楼层 使用 TABS 时至少需要两个商品组", error.message)
        verify(planRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(HomeRecommendationPlan::class.java))
    }

    @Test
    fun `manual selection rejects missing referenced products`() {
        val manualGroup = groupCommand(
            selectionMode = HomeRecommendationGroup.SelectionMode.MANUAL,
            strategy = HomeRecommendationGroup.Strategy.EDITOR_PICKS,
            items = listOf(AdminHomeRecommendationService.ItemCommand(88, false, null, 0)),
        )
        `when`(productRepository.findAllById(setOf(88L))).thenReturn(emptyList())

        val error = assertFailsWith<ParamErrorException> {
            service.create(99, createCommand(sections = listOf(sectionCommand(groups = listOf(manualGroup)))))
        }

        assertEquals("商品不存在：88", error.message)
        verify(planRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(HomeRecommendationPlan::class.java))
    }

    @Test
    fun `hero carousel requires carousel display style`() {
        val error = assertFailsWith<ParamErrorException> {
            service.create(
                99,
                createCommand(
                    sections = listOf(
                        sectionCommand(
                            code = HomeRecommendationSection.HERO_CAROUSEL_CODE,
                            groups = listOf(heroGroupCommand()),
                        ),
                    ),
                ),
            )
        }

        assertEquals("首页顶部轮播的展示形态必须为 CAROUSEL", error.message)
    }

    @Test
    fun `hero carousel must be the first section`() {
        val error = assertFailsWith<ParamErrorException> {
            service.create(
                99,
                createCommand(
                    sections = listOf(
                        sectionCommand(),
                        sectionCommand(
                            code = HomeRecommendationSection.HERO_CAROUSEL_CODE,
                            displayStyle = HomeRecommendationSection.DisplayStyle.CAROUSEL,
                            sortOrder = 1,
                            groups = listOf(heroGroupCommand()),
                        ),
                    ),
                ),
            )
        }

        assertEquals("首页顶部轮播必须位于第一个楼层且排序值为 0", error.message)
    }

    @Test
    fun `hero carousel requires manual selection`() {
        val error = assertFailsWith<ParamErrorException> {
            service.create(
                99,
                createCommand(
                    sections = listOf(
                        sectionCommand(
                            code = HomeRecommendationSection.HERO_CAROUSEL_CODE,
                            displayStyle = HomeRecommendationSection.DisplayStyle.CAROUSEL,
                            groups = listOf(
                                groupCommand(
                                    selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                                    strategy = HomeRecommendationGroup.Strategy.EDITOR_PICKS,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals("首页顶部轮播必须使用人工选品", error.message)
    }

    @Test
    fun `hero carousel accepts at most eight products`() {
        val items = (1L..9L).mapIndexed { index, productId ->
            AdminHomeRecommendationService.ItemCommand(productId, false, null, index)
        }

        val error = assertFailsWith<ParamErrorException> {
            service.create(
                99,
                createCommand(
                    sections = listOf(
                        sectionCommand(
                            code = HomeRecommendationSection.HERO_CAROUSEL_CODE,
                            displayStyle = HomeRecommendationSection.DisplayStyle.CAROUSEL,
                            groups = listOf(heroGroupCommand(items = items)),
                        ),
                    ),
                ),
            )
        }

        assertEquals("首页顶部轮播最多只能配置 8 个商品", error.message)
    }

    @Test
    fun `publish rejects hero carousel without effective products`() {
        val heroGroup = HomeRecommendationGroup(
            code = "hero_products",
            title = "首页顶部轮播商品",
            selectionMode = HomeRecommendationGroup.SelectionMode.MANUAL,
            strategy = HomeRecommendationGroup.Strategy.EDITOR_PICKS,
            itemLimit = HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS,
            minimumStock = 1,
            fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.NONE,
        ).apply {
            replaceItems(listOf(HomeRecommendationItem(productId = 1, sortOrder = 0)))
        }
        val heroSection = HomeRecommendationSection(
            code = HomeRecommendationSection.HERO_CAROUSEL_CODE,
            title = "Shop the featured edit.",
            displayStyle = HomeRecommendationSection.DisplayStyle.CAROUSEL,
            itemLimit = HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS,
            sortOrder = 0,
        ).apply { replaceGroups(listOf(heroGroup)) }
        val plan = plan(id = 7).apply { replaceSections(listOf(heroSection)) }
        val resolvedGroup = HomeRecommendationService.ResolvedGroup(
            id = null,
            code = "hero_products",
            title = "首页顶部轮播商品",
            selectionMode = HomeRecommendationGroup.SelectionMode.MANUAL,
            strategy = HomeRecommendationGroup.Strategy.EDITOR_PICKS,
            minimumStock = 1,
            products = emptyList(),
        )
        `when`(planRepository.findByIdForUpdate(7)).thenReturn(plan)
        `when`(productRepository.findAllById(setOf(1L))).thenReturn(listOf(Product(id = 1)))
        `when`(homeRecommendationService.resolve(plan, 20, null, false)).thenReturn(
            resolvedPlan(
                listOf(
                    HomeRecommendationService.ResolvedSection(
                        id = null,
                        code = HomeRecommendationSection.HERO_CAROUSEL_CODE,
                        eyebrow = null,
                        title = "Shop the featured edit.",
                        subtitle = null,
                        displayStyle = HomeRecommendationSection.DisplayStyle.CAROUSEL,
                        desktopColumns = 4,
                        mobileColumns = 2,
                        linkLabel = null,
                        linkUrl = null,
                        groups = listOf(resolvedGroup),
                    ),
                ),
            ),
        )

        val error = assertFailsWith<ParamErrorException> { service.publish(99, 7, 0) }

        assertEquals("首页顶部轮播解析后没有可展示商品", error.message)
        verify(planRepository, never()).saveAndFlush(plan)
    }

    @Test
    fun `publish rejects tabs with fewer than two effective groups`() {
        val first = automaticGroup("new_arrivals")
        val second = automaticGroup("best_sellers", HomeRecommendationGroup.Strategy.BEST_SELLERS)
        val section = HomeRecommendationSection(
            code = "whats_hot",
            title = "What's hot",
            displayStyle = HomeRecommendationSection.DisplayStyle.TABS,
        ).apply { replaceGroups(listOf(first, second)) }
        val plan = plan(id = 7).apply { replaceSections(listOf(section)) }
        val oneEffectiveGroup = HomeRecommendationService.ResolvedGroup(
            id = null,
            code = "new_arrivals",
            title = "New arrivals",
            selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
            strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
            minimumStock = 1,
            products = listOf(resolvedProduct()),
        )
        `when`(planRepository.findByIdForUpdate(7)).thenReturn(plan)
        `when`(homeRecommendationService.resolve(plan, 20, null, false)).thenReturn(
            resolvedPlan(
                listOf(
                    HomeRecommendationService.ResolvedSection(
                        id = null,
                        code = "whats_hot",
                        eyebrow = null,
                        title = "What's hot",
                        subtitle = null,
                        displayStyle = HomeRecommendationSection.DisplayStyle.TABS,
                        desktopColumns = 4,
                        mobileColumns = 2,
                        linkLabel = null,
                        linkUrl = null,
                        groups = listOf(oneEffectiveGroup),
                    ),
                ),
            ),
        )

        val error = assertFailsWith<ParamErrorException> { service.publish(99, 7, 0) }

        assertEquals("TABS 楼层 whats_hot 少于两个有效商品组", error.message)
        verify(planRepository, never()).saveAndFlush(plan)
    }

    @Test
    fun `immediate publish atomically offlines the previous published plan`() {
        val plan = plan(id = 7)
        val previous = plan(id = 6).apply { status = HomeRecommendationPlan.Status.PUBLISHED }
        `when`(planRepository.findByIdForUpdate(7)).thenReturn(plan)
        `when`(homeRecommendationService.resolve(plan, 20, null, false)).thenReturn(resolvedPlan(emptyList()))
        `when`(
            planRepository.findActiveForUpdate(
                HomeRecommendationPlan.Channel.CUSTOMER_WEB,
                listOf(HomeRecommendationPlan.Status.PUBLISHED, HomeRecommendationPlan.Status.SCHEDULED),
            ),
        ).thenReturn(listOf(previous))
        `when`(planRepository.saveAndFlush(plan)).thenReturn(plan)

        val published = service.publish(99, 7, 0)

        assertEquals(HomeRecommendationPlan.Status.PUBLISHED, published?.status)
        assertEquals(HomeRecommendationPlan.Status.OFFLINE, previous.status)
        assertEquals(99, previous.updatedBy)
        assertEquals(currentTime, published?.publishedAt)
    }

    @Test
    fun `future publish is scheduled without setting actual published time`() {
        val plan = plan(id = 7).apply {
            effectiveFrom = currentTime.plusHours(2)
            effectiveUntil = currentTime.plusDays(1)
        }
        `when`(planRepository.findByIdForUpdate(7)).thenReturn(plan)
        `when`(homeRecommendationService.resolve(plan, 20, null, false)).thenReturn(resolvedPlan(emptyList()))
        `when`(
            planRepository.findActiveForUpdate(
                HomeRecommendationPlan.Channel.CUSTOMER_WEB,
                listOf(HomeRecommendationPlan.Status.PUBLISHED, HomeRecommendationPlan.Status.SCHEDULED),
            ),
        ).thenReturn(emptyList())
        `when`(planRepository.saveAndFlush(plan)).thenReturn(plan)

        val scheduled = service.publish(99, 7, 0)

        assertEquals(HomeRecommendationPlan.Status.SCHEDULED, scheduled?.status)
        assertEquals(null, scheduled?.publishedAt)
    }

    @Test
    fun `future publish rejects an overlapping active interval`() {
        val plan = plan(id = 7).apply {
            effectiveFrom = currentTime.plusHours(2)
            effectiveUntil = currentTime.plusDays(1)
        }
        val existing = plan(id = 6).apply {
            status = HomeRecommendationPlan.Status.SCHEDULED
            effectiveFrom = currentTime.plusHours(3)
            effectiveUntil = currentTime.plusDays(2)
        }
        `when`(planRepository.findByIdForUpdate(7)).thenReturn(plan)
        `when`(homeRecommendationService.resolve(plan, 20, null, false)).thenReturn(resolvedPlan(emptyList()))
        `when`(
            planRepository.findActiveForUpdate(
                HomeRecommendationPlan.Channel.CUSTOMER_WEB,
                listOf(HomeRecommendationPlan.Status.PUBLISHED, HomeRecommendationPlan.Status.SCHEDULED),
            ),
        ).thenReturn(listOf(existing))

        val error = assertFailsWith<HomeRecommendationScheduleConflictException> {
            service.publish(99, 7, 0)
        }

        assertEquals("方案生效区间与已排期方案 #6 重叠", error.message)
        verify(planRepository, never()).saveAndFlush(plan)
    }

    @Test
    fun `lifecycle publishes only the latest due plan for a channel`() {
        val current = plan(id = 5).apply { status = HomeRecommendationPlan.Status.PUBLISHED }
        val older = plan(id = 6).apply {
            status = HomeRecommendationPlan.Status.SCHEDULED
            effectiveFrom = currentTime.minusHours(2)
            publishedAt = currentTime.minusDays(1)
        }
        val latest = plan(id = 7).apply {
            status = HomeRecommendationPlan.Status.SCHEDULED
            effectiveFrom = currentTime.minusHours(1)
            publishedAt = null
        }
        `when`(
            planRepository.findDueForExpiration(
                listOf(HomeRecommendationPlan.Status.SCHEDULED, HomeRecommendationPlan.Status.PUBLISHED),
                currentTime,
            ),
        ).thenReturn(emptyList())
        `when`(planRepository.findDueForPublication(HomeRecommendationPlan.Status.SCHEDULED, currentTime))
            .thenReturn(listOf(latest, older))
        `when`(
            planRepository.findActiveForUpdate(
                HomeRecommendationPlan.Channel.CUSTOMER_WEB,
                listOf(HomeRecommendationPlan.Status.PUBLISHED),
            ),
        ).thenReturn(listOf(current))

        val changed = service.synchronizeLifecycle()

        assertEquals(2, changed)
        assertEquals(HomeRecommendationPlan.Status.OFFLINE, current.status)
        assertEquals(HomeRecommendationPlan.Status.OFFLINE, older.status)
        assertEquals(HomeRecommendationPlan.Status.PUBLISHED, latest.status)
        assertEquals(currentTime, latest.publishedAt)
        verify(planRepository, times(1)).flush()
    }

    private fun plan(id: Long, version: Long = 0) = HomeRecommendationPlan(
        id = id,
        version = version,
        name = "Homepage",
        status = HomeRecommendationPlan.Status.DRAFT,
        effectiveFrom = currentTime.minusHours(1),
        fallbackEnabled = true,
        createdBy = 99,
        updatedBy = 99,
    ).apply {
        replaceSections(listOf(HomeRecommendationSection(code = "featured", title = "Featured").apply {
            replaceGroups(listOf(automaticGroup("featured")))
        }))
    }

    private fun automaticGroup(
        code: String,
        strategy: HomeRecommendationGroup.Strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
    ) = HomeRecommendationGroup(
        code = code,
        title = code.replace('_', ' '),
        selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
        strategy = strategy,
        lookbackDays = if (strategy == HomeRecommendationGroup.Strategy.NEW_ARRIVALS) 30 else null,
        fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.NONE,
    )

    private fun createCommand(
        sections: List<AdminHomeRecommendationService.SectionCommand>,
        categories: List<AdminHomeRecommendationService.CategoryCommand> = emptyList(),
    ) =
        AdminHomeRecommendationService.CreateCommand(
            name = "Homepage",
            effectiveFrom = currentTime,
            effectiveUntil = currentTime.plusDays(1),
            fallbackEnabled = true,
            deduplicateAcrossSections = true,
            categories = categories,
            sections = sections,
        )

    private fun updateCommand(expectedVersion: Long) = AdminHomeRecommendationService.UpdateCommand(
        name = "Homepage",
        effectiveFrom = currentTime,
        effectiveUntil = currentTime.plusDays(1),
        fallbackEnabled = true,
        deduplicateAcrossSections = true,
        sections = listOf(sectionCommand()),
        expectedVersion = expectedVersion,
    )

    private fun sectionCommand(
        code: String = "featured",
        displayStyle: HomeRecommendationSection.DisplayStyle = HomeRecommendationSection.DisplayStyle.GRID,
        itemLimit: Int = 8,
        sortOrder: Int = 0,
        groups: List<AdminHomeRecommendationService.GroupCommand> = listOf(groupCommand()),
    ) = AdminHomeRecommendationService.SectionCommand(
        code = code,
        eyebrow = null,
        title = "Featured",
        subtitle = null,
        displayStyle = displayStyle,
        desktopColumns = 4,
        mobileColumns = 2,
        linkLabel = null,
        linkUrl = null,
        itemLimit = itemLimit,
        hideWhenEmpty = true,
        sortOrder = sortOrder,
        groups = groups,
    )

    private fun groupCommand(
        code: String = "featured",
        selectionMode: HomeRecommendationGroup.SelectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
        strategy: HomeRecommendationGroup.Strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
        itemLimit: Int = 8,
        fallbackStrategy: HomeRecommendationGroup.FallbackStrategy = HomeRecommendationGroup.FallbackStrategy.NONE,
        items: List<AdminHomeRecommendationService.ItemCommand> = emptyList(),
    ) = AdminHomeRecommendationService.GroupCommand(
        code = code,
        title = "Featured",
        selectionMode = selectionMode,
        strategy = strategy,
        itemLimit = itemLimit,
        categoryId = null,
        productType = null,
        tagId = null,
        lookbackDays = if (strategy == HomeRecommendationGroup.Strategy.NEW_ARRIVALS) 30 else null,
        minimumStock = 1,
        fallbackStrategy = fallbackStrategy,
        sortOrder = 0,
        items = items,
    )

    private fun heroGroupCommand(
        items: List<AdminHomeRecommendationService.ItemCommand> = listOf(
            AdminHomeRecommendationService.ItemCommand(1, false, null, 0),
        ),
    ) = groupCommand(
        code = "hero_products",
        selectionMode = HomeRecommendationGroup.SelectionMode.MANUAL,
        strategy = HomeRecommendationGroup.Strategy.EDITOR_PICKS,
        itemLimit = HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS,
        fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.NONE,
        items = items,
    )

    private fun resolvedProduct() = HomeRecommendationService.ResolvedProduct(
        product = HomeRecommendationService.ProductData(
            id = 1,
            productType = "SWIMWEAR",
            categoryId = null,
            name = "Product",
            status = top.foxball.shopmall.entity.jdbc.Product.Status.ACTIVE,
            highlights = emptyList(),
            materials = emptyList(),
            images = emptyList(),
            attributes = emptyList(),
            fitSense = null,
            description = null,
            designAndExtras = emptyList(),
            careInstructions = emptyList(),
            tags = emptyList(),
            score = null,
            variants = emptyList(),
            createdAt = null,
            updatedAt = null,
        ),
        badge = null,
        context = HomeRecommendationService.RecommendationContext(
            requestId = "rec_test",
            planId = 7,
            sectionCode = "whats_hot",
            groupCode = "new_arrivals",
            strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
            position = 1,
        ),
    )

    private fun resolvedPlan(sections: List<HomeRecommendationService.ResolvedSection>) =
        HomeRecommendationService.ResolvedPlan(
            planId = 7,
            planVersion = 0,
            requestId = "rec_test",
            generatedAt = currentTime,
            expiresAt = currentTime.plusMinutes(1),
            sections = sections,
            fallback = false,
        )
}
