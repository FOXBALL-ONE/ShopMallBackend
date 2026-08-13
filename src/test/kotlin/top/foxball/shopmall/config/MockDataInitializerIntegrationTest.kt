package top.foxball.shopmall.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.repository.HomeRecommendationPlanRepository
import top.foxball.shopmall.service.HomeRecommendationService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["shopmall.mock-data.enabled=true"])
@ActiveProfiles("test")
@Transactional
class MockDataInitializerIntegrationTest {
    @Autowired
    private lateinit var homeRecommendationPlanRepository: HomeRecommendationPlanRepository

    @Autowired
    private lateinit var homeRecommendationService: HomeRecommendationService

    @Test
    fun `mock initialization creates recommendation plans for storefront and admin workflows`() {
        val plans = homeRecommendationPlanRepository.findAll()

        assertEquals(3, plans.size)
        assertEquals(
            setOf(
                HomeRecommendationPlan.Status.PUBLISHED,
                HomeRecommendationPlan.Status.SCHEDULED,
                HomeRecommendationPlan.Status.DRAFT,
            ),
            plans.map(HomeRecommendationPlan::status).toSet(),
        )

        val published = plans.single { it.status == HomeRecommendationPlan.Status.PUBLISHED }
        assertEquals(listOf("editor_picks", "shop_the_latest"), published.sections.map { it.code })
        assertEquals(
            HomeRecommendationSection.DisplayStyle.TABS,
            published.sections.single { it.code == "shop_the_latest" }.displayStyle,
        )
        assertTrue(
            published.sections.single { it.code == "editor_picks" }
                .groups.single().items.isNotEmpty(),
        )

        val draft = plans.single { it.status == HomeRecommendationPlan.Status.DRAFT }
        assertEquals(
            HomeRecommendationGroup.SelectionMode.MANUAL,
            draft.sections.single().groups.single().selectionMode,
        )
        assertFalse(draft.sections.single().groups.single().items.isEmpty())

        val current = homeRecommendationService.current()
        assertEquals(published.id, current.planId)
        assertFalse(current.fallback)
        assertNotNull(current.sections.firstOrNull { it.code == "editor_picks" })
        assertTrue(current.sections.flatMap { it.groups }.any { it.products.isNotEmpty() })
    }
}
