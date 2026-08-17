package top.foxball.shopmall.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.service.HomeRecommendationService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

class HomeRecommendationControllerTest {
    private lateinit var homeRecommendationService: HomeRecommendationService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        homeRecommendationService = mock(HomeRecommendationService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            HomeRecommendationController(homeRecommendationService, ResponseBuilder()),
        ).build()
    }

    @Test
    fun `recommendations use snake case ISO date time and the configured stock threshold`() {
        val product = HomeRecommendationService.ProductData(
            id = 12,
            productType = "SWIMWEAR",
            categoryId = null,
            name = "Black swimsuit",
            status = Product.Status.ACTIVE,
            highlights = emptyList(),
            materials = emptyList(),
            images = listOf(
                HomeRecommendationService.ProductImageData(
                    url = "https://example.com/product.jpg",
                    altText = null,
                    primary = true,
                    sortOrder = 0,
                ),
            ),
            attributes = emptyList(),
            fitSense = null,
            description = null,
            designAndExtras = emptyList(),
            careInstructions = emptyList(),
            tags = emptyList(),
            score = null,
            variants = listOf(
                HomeRecommendationService.ProductVariantData(
                    id = 121,
                    sku = "LOW-STOCK",
                    size = null,
                    color = "Black",
                    price = BigDecimal("19.99"),
                    warehouseVolume = 4,
                    salesVolume = 0,
                    displayOrder = 0,
                    status = ProductVariant.Status.ACTIVE,
                    attributes = emptyList(),
                ),
                HomeRecommendationService.ProductVariantData(
                    id = 122,
                    sku = "READY-STOCK",
                    size = null,
                    color = "Black",
                    price = BigDecimal("29.99"),
                    warehouseVolume = 5,
                    salesVolume = 0,
                    displayOrder = 1,
                    status = ProductVariant.Status.ACTIVE,
                    attributes = emptyList(),
                ),
            ),
            createdAt = LocalDateTime.of(2026, 8, 10, 9, 30),
            updatedAt = LocalDateTime.of(2026, 8, 11, 10, 45),
        )
        val context = HomeRecommendationService.RecommendationContext(
            requestId = "rec_test",
            planId = 9,
            sectionCode = "whats_hot",
            groupCode = "new_arrivals",
            strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
            position = 1,
        )
        val resolvedGroup = HomeRecommendationService.ResolvedGroup(
            id = 3,
            code = "new_arrivals",
            title = "New arrivals",
            selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
            strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
            minimumStock = 5,
            products = listOf(HomeRecommendationService.ResolvedProduct(product, "NEW", context)),
        )
        val generatedAt = LocalDateTime.of(2026, 8, 12, 12, 30, 15)
        `when`(homeRecommendationService.current(10, null)).thenReturn(
            HomeRecommendationService.ResolvedPlan(
                planId = 9,
                planVersion = 2,
                requestId = "rec_test",
                generatedAt = generatedAt,
                expiresAt = generatedAt.plusMinutes(1),
                categoriesConfigured = true,
                categories = listOf(
                    HomeRecommendationService.ResolvedCategory(
                        id = 4,
                        categoryId = 6,
                        code = "swimwear",
                        name = "Swimwear",
                        imageUrl = "/images/swimwear.jpg",
                        altText = "Swimwear collection",
                    ),
                ),
                sections = listOf(
                    HomeRecommendationService.ResolvedSection(
                        id = 2,
                        code = "whats_hot",
                        eyebrow = "TRENDING NOW",
                        title = "What's hot",
                        subtitle = null,
                        displayStyle = HomeRecommendationSection.DisplayStyle.TABS,
                        desktopColumns = 4,
                        mobileColumns = 2,
                        linkLabel = "Shop all",
                        linkUrl = "/collections/shop",
                        groups = listOf(resolvedGroup),
                    ),
                ),
                fallback = false,
            ),
        )

        mockMvc.perform(get("/api/home/recommendations"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "public, max-age=30, stale-while-revalidate=60"))
            .andExpect(jsonPath("$.data.plan_id").value(9))
            .andExpect(jsonPath("$.data.plan_version").value(2))
            .andExpect(jsonPath("$.data.generated_at").value("2026-08-12T12:30:15"))
            .andExpect(jsonPath("$.data.categories_configured").value(true))
            .andExpect(jsonPath("$.data.categories[0].category_id").value(6))
            .andExpect(jsonPath("$.data.categories[0].image_url").value("/images/swimwear.jpg"))
            .andExpect(jsonPath("$.data.categories[0].alt_text").value("Swimwear collection"))
            .andExpect(jsonPath("$.data.sections[0].display_style").value("TABS"))
            .andExpect(jsonPath("$.data.sections[0].desktop_columns").value(4))
            .andExpect(jsonPath("$.data.sections[0].groups[0].selection_mode").value("AUTO"))
            .andExpect(jsonPath("$.data.sections[0].groups[0].products[0].recommendation_context.group_code").value("new_arrivals"))
            .andExpect(jsonPath("$.data.sections[0].groups[0].products[0].variants.length()").value(1))
            .andExpect(jsonPath("$.data.sections[0].groups[0].products[0].variants[0].sku").value("READY-STOCK"))
            .andExpect(jsonPath("$.data.sections[0].groups[0].products[0].variants[0].currency").value("USD"))
            .andExpect(jsonPath("$.data.sections[0].groups[0].products[0].variants[0].warehouse_volume").value(5))
    }
}
