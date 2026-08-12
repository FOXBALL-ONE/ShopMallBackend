package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.Product
import java.math.BigDecimal
import java.time.LocalDateTime

interface HomeRecommendationService {
    data class RecommendationContext(
        val requestId: String,
        val planId: Long?,
        val sectionCode: String,
        val groupCode: String,
        val strategy: HomeRecommendationGroup.Strategy,
        val position: Int,
    )

    data class ProductAttributeData(
        val code: String,
        val value: String,
    )

    data class ProductMaterialData(
        val name: String,
        val percentage: BigDecimal,
    )

    data class ProductImageData(
        val url: String,
        val altText: String?,
        val primary: Boolean,
        val sortOrder: Int,
    )

    data class ProductVariantData(
        val id: Long,
        val sku: String,
        val size: String?,
        val color: String,
        val price: BigDecimal,
        val warehouseVolume: Int,
        val salesVolume: Long,
        val displayOrder: Int,
        val status: top.foxball.shopmall.entity.jdbc.ProductVariant.Status,
        val attributes: List<ProductAttributeData>,
    )

    data class ProductData(
        val id: Long,
        val productType: String,
        val categoryId: Long?,
        val name: String,
        val status: Product.Status,
        val highlights: List<String>,
        val materials: List<ProductMaterialData>,
        val images: List<ProductImageData>,
        val attributes: List<ProductAttributeData>,
        val fitSense: String?,
        val description: String?,
        val designAndExtras: List<String>,
        val careInstructions: List<String>,
        val tags: List<String>,
        val score: Float?,
        val variants: List<ProductVariantData>,
        val createdAt: LocalDateTime?,
        val updatedAt: LocalDateTime?,
    )

    data class ResolvedProduct(
        val product: ProductData,
        val badge: String?,
        val context: RecommendationContext,
    )

    data class ResolvedGroup(
        val id: Long?,
        val code: String,
        val title: String?,
        val selectionMode: HomeRecommendationGroup.SelectionMode,
        val strategy: HomeRecommendationGroup.Strategy,
        val minimumStock: Int,
        val products: List<ResolvedProduct>,
    )

    data class ResolvedSection(
        val id: Long?,
        val code: String,
        val eyebrow: String?,
        val title: String,
        val subtitle: String?,
        val displayStyle: HomeRecommendationSection.DisplayStyle,
        val desktopColumns: Int,
        val mobileColumns: Int,
        val linkLabel: String?,
        val linkUrl: String?,
        val groups: List<ResolvedGroup>,
    )

    data class ResolvedPlan(
        val planId: Long?,
        val planVersion: Long,
        val requestId: String,
        val generatedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
        val sections: List<ResolvedSection>,
        val fallback: Boolean,
    )

    fun current(sectionLimit: Int = 10, productLimitPerGroup: Int? = null): ResolvedPlan

    fun resolve(
        plan: HomeRecommendationPlan,
        sectionLimit: Int = 20,
        productLimitPerGroup: Int? = null,
        useDefaultFallback: Boolean = true,
    ): ResolvedPlan
}

interface AdminHomeRecommendationService {
    enum class SortBy(val property: String) {
        UPDATED_AT("updatedAt"),
        CREATED_AT("createdAt"),
        EFFECTIVE_FROM("effectiveFrom"),
        NAME("name"),
    }

    data class Query(
        val page: Int = 0,
        val size: Int = 25,
        val keyword: String? = null,
        val status: HomeRecommendationPlan.Status? = null,
        val sortBy: SortBy = SortBy.UPDATED_AT,
        val ascending: Boolean = false,
    )

    data class ItemCommand(
        val productId: Long,
        val pinned: Boolean = false,
        val customBadge: String? = null,
        val sortOrder: Int,
    )

    data class GroupCommand(
        val code: String,
        val title: String?,
        val selectionMode: HomeRecommendationGroup.SelectionMode,
        val strategy: HomeRecommendationGroup.Strategy,
        val itemLimit: Int,
        val categoryId: Long?,
        val productType: String?,
        val tagId: Long?,
        val lookbackDays: Int?,
        val minimumStock: Int,
        val fallbackStrategy: HomeRecommendationGroup.FallbackStrategy,
        val sortOrder: Int,
        val items: List<ItemCommand>,
    )

    data class SectionCommand(
        val code: String,
        val eyebrow: String?,
        val title: String,
        val subtitle: String?,
        val displayStyle: HomeRecommendationSection.DisplayStyle,
        val desktopColumns: Int,
        val mobileColumns: Int,
        val linkLabel: String?,
        val linkUrl: String?,
        val itemLimit: Int,
        val hideWhenEmpty: Boolean,
        val sortOrder: Int,
        val groups: List<GroupCommand>,
    )

    data class CreateCommand(
        val name: String,
        val effectiveFrom: LocalDateTime,
        val effectiveUntil: LocalDateTime?,
        val fallbackEnabled: Boolean,
        val deduplicateAcrossSections: Boolean,
        val sections: List<SectionCommand>,
    )

    data class UpdateCommand(
        val name: String,
        val effectiveFrom: LocalDateTime,
        val effectiveUntil: LocalDateTime?,
        val fallbackEnabled: Boolean,
        val deduplicateAcrossSections: Boolean,
        val sections: List<SectionCommand>,
        val expectedVersion: Long,
    )

    fun list(adminId: Long, query: Query): Page<HomeRecommendationPlan>

    fun get(adminId: Long, planId: Long): HomeRecommendationPlan?

    fun create(adminId: Long, command: CreateCommand): HomeRecommendationPlan

    fun update(adminId: Long, planId: Long, command: UpdateCommand): HomeRecommendationPlan?

    fun copy(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan?

    fun publish(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan?

    fun offline(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan?

    fun archive(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan?

    fun preview(adminId: Long, planId: Long, productLimitPerGroup: Int? = null): HomeRecommendationService.ResolvedPlan?

    fun synchronizeLifecycle(): Int
}
