package top.foxball.shopmall.repository

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductImage
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HomeRecommendationRepositoryIntegrationTest {
    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var productTypeRepository: ProductTypeRepository

    @Autowired
    private lateinit var planRepository: HomeRecommendationPlanRepository

    @Test
    fun `best seller candidates are ordered by summed variant sales and exclude unsellable products`() {
        val productType = productTypeRepository.saveAndFlush(ProductType(code = "REC_TEST", name = "Recommendation test"))
        val lowerSeller = productRepository.saveAndFlush(product(productType, "Lower seller", "LOW", sales = listOf(4, 5)))
        val bestSeller = productRepository.saveAndFlush(product(productType, "Best seller", "BEST", sales = listOf(20)))
        productRepository.saveAndFlush(product(productType, "Inactive product", "INACTIVE", sales = listOf(100)).apply {
            status = Product.Status.INACTIVE
        })
        productRepository.saveAndFlush(product(productType, "No image", "NOIMAGE", sales = listOf(100)).apply {
            images.clear()
        })
        productRepository.saveAndFlush(product(productType, "No stock", "NOSTOCK", sales = listOf(100), stock = 0))
        productRepository.saveAndFlush(product(productType, "Deleted", "DELETED", sales = listOf(100)).apply {
            deletedAt = LocalDateTime.of(2026, 8, 11, 12, 0)
        })

        val candidates = productRepository.findBestSellerRecommendationCandidates(
            Product.Status.ACTIVE,
            ProductVariant.Status.ACTIVE,
            1,
            null,
            null,
            null,
            PageRequest.of(0, 20),
        )

        assertEquals(listOf(bestSeller.id, lowerSeller.id), candidates.map(Product::id))
    }

    @Test
    fun `high rated candidates use active variant sales as the score tie break`() {
        val productType = productTypeRepository.saveAndFlush(ProductType(code = "REC_RATED", name = "Rated recommendation test"))
        val lowerSeller = productRepository.saveAndFlush(
            product(productType, "Lower rated seller", "RATED-LOW", sales = listOf(4, 5)).apply { score = 4.8f },
        )
        val higherSeller = productRepository.saveAndFlush(
            product(productType, "Higher rated seller", "RATED-HIGH", sales = listOf(20)).apply { score = 4.8f },
        )

        val candidates = productRepository.findHighRatedRecommendationCandidates(
            Product.Status.ACTIVE,
            ProductVariant.Status.ACTIVE,
            1,
            null,
            productType.code,
            null,
            PageRequest.of(0, 20),
        )

        assertEquals(listOf(higherSeller.id, lowerSeller.id), candidates.map(Product::id))
    }

    @Test
    fun `current plan includes effective from and excludes effective until boundaries`() {
        val currentTime = LocalDateTime.of(2026, 8, 12, 12, 0)
        val current = planRepository.saveAndFlush(
            HomeRecommendationPlan(
                name = "Current",
                status = HomeRecommendationPlan.Status.PUBLISHED,
                effectiveFrom = currentTime,
                effectiveUntil = currentTime.plusDays(1),
                createdBy = 99,
                updatedBy = 99,
            ),
        )
        planRepository.saveAndFlush(
            HomeRecommendationPlan(
                name = "Expired at boundary",
                status = HomeRecommendationPlan.Status.PUBLISHED,
                effectiveFrom = currentTime.minusDays(1),
                effectiveUntil = currentTime,
                createdBy = 99,
                updatedBy = 99,
            ),
        )

        val plans = planRepository.findCurrent(
            HomeRecommendationPlan.Channel.CUSTOMER_WEB,
            HomeRecommendationPlan.Status.PUBLISHED,
            currentTime,
            PageRequest.of(0, 10),
        )

        assertEquals(listOf(current.id), plans.map(HomeRecommendationPlan::id))
    }

    private fun product(
        productType: ProductType,
        name: String,
        skuPrefix: String,
        sales: List<Long>,
        stock: Int = 10,
    ) = Product(
        productType = productType,
        name = name,
        status = Product.Status.ACTIVE,
        images = mutableListOf(ProductImage(url = "https://example.com/$skuPrefix.jpg", primary = true)),
    ).apply {
        sales.forEachIndexed { index, salesVolume ->
            addVariant(
                ProductVariant(
                    sku = "$skuPrefix-$index",
                    color = "Black",
                    price = BigDecimal("29.99"),
                    warehouseVolume = stock,
                    salesVolume = salesVolume,
                    status = ProductVariant.Status.ACTIVE,
                    optionSignature = "variant=$index",
                ),
            )
        }
    }
}
