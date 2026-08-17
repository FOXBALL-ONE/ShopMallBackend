package top.foxball.shopmall.service

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.HomeRecommendationCategory
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.HomeRecommendationPlanRepository
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.UserRepository
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminHomeRecommendationServiceIntegrationTest {
    @Autowired
    private lateinit var service: AdminHomeRecommendationService

    @Autowired
    private lateinit var planRepository: HomeRecommendationPlanRepository

    @Autowired
    private lateinit var productCategoryRepository: ProductCategoryRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `updating a plan can reuse category and section unique keys`() {
        val admin = userRepository.saveAndFlush(
            User(
                email = "home-recommendation-update-admin@example.test",
                username = "home_recommendation_update_admin",
                password = "test-password",
                role = Role.ADMIN,
            ),
        )
        val category = productCategoryRepository.saveAndFlush(
            ProductCategory(code = "update-test", name = "Update test"),
        )
        val plan = HomeRecommendationPlan(
            name = "Original plan",
            effectiveFrom = LocalDateTime.of(2026, 8, 17, 12, 0),
            fallbackEnabled = true,
            createdBy = requireNotNull(admin.id),
            updatedBy = requireNotNull(admin.id),
        ).apply {
            replaceCategories(
                listOf(
                    HomeRecommendationCategory(
                        categoryId = requireNotNull(category.id),
                        imageUrl = "/images/original.jpg",
                        sortOrder = 0,
                    ),
                ),
            )
            replaceSections(
                listOf(
                    HomeRecommendationSection(
                        code = "editor_picks",
                        title = "Original title",
                        displayStyle = HomeRecommendationSection.DisplayStyle.GRID,
                        itemLimit = 8,
                    ).apply {
                        replaceGroups(
                            listOf(
                                HomeRecommendationGroup(
                                    code = "featured",
                                    selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                                    strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
                                    itemLimit = 8,
                                    lookbackDays = 30,
                                    minimumStock = 1,
                                    fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.LATEST,
                                ),
                            ),
                        )
                    },
                ),
            )
        }
        val saved = planRepository.saveAndFlush(plan)
        val planId = requireNotNull(saved.id)
        val expectedVersion = saved.version
        entityManager.clear()

        service.update(
            requireNotNull(admin.id),
            planId,
            AdminHomeRecommendationService.UpdateCommand(
                name = "Updated plan",
                effectiveFrom = LocalDateTime.of(2026, 8, 17, 13, 0),
                effectiveUntil = null,
                fallbackEnabled = true,
                deduplicateAcrossSections = true,
                categories = listOf(
                    AdminHomeRecommendationService.CategoryCommand(
                        categoryId = requireNotNull(category.id),
                        imageUrl = "/images/updated.jpg",
                        altText = "Updated category image",
                        sortOrder = 0,
                    ),
                ),
                sections = listOf(
                    AdminHomeRecommendationService.SectionCommand(
                        code = "editor_picks",
                        eyebrow = null,
                        title = "Updated title",
                        subtitle = null,
                        displayStyle = HomeRecommendationSection.DisplayStyle.GRID,
                        desktopColumns = 4,
                        mobileColumns = 2,
                        linkLabel = null,
                        linkUrl = null,
                        itemLimit = 8,
                        hideWhenEmpty = true,
                        sortOrder = 0,
                        groups = listOf(
                            AdminHomeRecommendationService.GroupCommand(
                                code = "featured",
                                title = null,
                                selectionMode = HomeRecommendationGroup.SelectionMode.AUTO,
                                strategy = HomeRecommendationGroup.Strategy.NEW_ARRIVALS,
                                itemLimit = 8,
                                categoryId = null,
                                productType = null,
                                tagId = null,
                                lookbackDays = 30,
                                minimumStock = 1,
                                fallbackStrategy = HomeRecommendationGroup.FallbackStrategy.LATEST,
                                sortOrder = 0,
                                items = emptyList(),
                            ),
                        ),
                    ),
                ),
                expectedVersion = expectedVersion,
            ),
        )
        entityManager.clear()

        val updated = planRepository.findById(planId).orElseThrow()
        updated.categories.size
        updated.sections.forEach { section -> section.groups.size }
        assertEquals("Updated plan", updated.name)
        assertEquals(listOf("/images/updated.jpg"), updated.categories.map { it.imageUrl })
        assertEquals(listOf("editor_picks"), updated.sections.map { it.code })
        assertEquals(listOf("Updated title"), updated.sections.map { it.title })
    }

}
