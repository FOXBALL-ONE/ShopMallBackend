package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.service.HomeRecommendationService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/** 客户首页商品推荐聚合接口。 */
@Validated
@RestController
class HomeRecommendationController(
    private val homeRecommendationService: HomeRecommendationService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/home/recommendations")
    fun currentRecommendations(
        @RequestParam("section_limit", defaultValue = "10") @Min(1) @Max(20) sectionLimit: Int,
        @RequestParam("product_limit_per_group", required = false) @Min(1) @Max(24) productLimitPerGroup: Int?,
    ): ResponseEntity<Response> {
        data class CategoryData(
            val id: Long?,
            @param:JsonProperty("category_id") val categoryId: Long,
            val code: String,
            val name: String,
            @param:JsonProperty("image_url") val imageUrl: String,
            @param:JsonProperty("alt_text") val altText: String?,
        )
        data class AttributeData(val code: String, val value: String)
        data class MaterialData(val name: String, val percentage: String)
        data class ImageData(
            val url: String,
            @param:JsonProperty("alt_text") val altText: String?,
            @param:JsonProperty("is_primary") val primary: Boolean,
            @param:JsonProperty("sort_order") val sortOrder: Int,
        )
        data class VariantData(
            val id: Long,
            val sku: String,
            val size: String?,
            val color: String,
            val price: String,
            val currency: String,
            @param:JsonProperty("warehouse_volume") val warehouseVolume: Int,
            @param:JsonProperty("sales_volume") val salesVolume: Long,
            @param:JsonProperty("display_order") val displayOrder: Int,
            val attributes: List<AttributeData>,
        )
        data class RecommendationContextData(
            @param:JsonProperty("request_id") val requestId: String,
            @param:JsonProperty("plan_id") val planId: Long?,
            @param:JsonProperty("section_code") val sectionCode: String,
            @param:JsonProperty("group_code") val groupCode: String,
            val strategy: String,
            val position: Int,
        )
        data class ProductData(
            val id: Long,
            @param:JsonProperty("product_type") val productType: String,
            @param:JsonProperty("category_id") val categoryId: Long?,
            val name: String,
            val status: String,
            val highlights: List<String>,
            val materials: List<MaterialData>,
            val images: List<ImageData>,
            val attributes: List<AttributeData>,
            @param:JsonProperty("fit_sense") val fitSense: String?,
            val description: String?,
            @param:JsonProperty("design_and_extras") val designAndExtras: List<String>,
            @param:JsonProperty("care_instructions") val careInstructions: List<String>,
            val tags: List<String>,
            val score: Float?,
            val variants: List<VariantData>,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            val badge: String?,
            @param:JsonProperty("recommendation_context") val recommendationContext: RecommendationContextData,
        )
        data class GroupData(
            val id: Long?,
            val code: String,
            val title: String?,
            @param:JsonProperty("selection_mode") val selectionMode: String,
            val strategy: String,
            val products: List<ProductData>,
        )
        data class SectionData(
            val id: Long?,
            val code: String,
            val eyebrow: String?,
            val title: String,
            val subtitle: String?,
            @param:JsonProperty("display_style") val displayStyle: String,
            @param:JsonProperty("desktop_columns") val desktopColumns: Int,
            @param:JsonProperty("mobile_columns") val mobileColumns: Int,
            @param:JsonProperty("link_label") val linkLabel: String?,
            @param:JsonProperty("link_url") val linkUrl: String?,
            val groups: List<GroupData>,
        )
        data class Response(
            @param:JsonProperty("plan_id") val planId: Long?,
            @param:JsonProperty("plan_version") val planVersion: Long,
            @param:JsonProperty("request_id") val requestId: String,
            @param:JsonProperty("generated_at") val generatedAt: LocalDateTime,
            @param:JsonProperty("expires_at") val expiresAt: LocalDateTime,
            val fallback: Boolean,
            @param:JsonProperty("categories_configured") val categoriesConfigured: Boolean,
            val categories: List<CategoryData>,
            val sections: List<SectionData>,
        )

        val recommendation = homeRecommendationService.current(sectionLimit, productLimitPerGroup)
        val rs = Response(
            planId = recommendation.planId,
            planVersion = recommendation.planVersion,
            requestId = recommendation.requestId,
            generatedAt = recommendation.generatedAt,
            expiresAt = recommendation.expiresAt,
            fallback = recommendation.fallback,
            categoriesConfigured = recommendation.categoriesConfigured,
            categories = recommendation.categories.map { category ->
                CategoryData(
                    id = category.id,
                    categoryId = category.categoryId,
                    code = category.code,
                    name = category.name,
                    imageUrl = category.imageUrl,
                    altText = category.altText,
                )
            },
            sections = recommendation.sections.map { section ->
                SectionData(
                    id = section.id,
                    code = section.code,
                    eyebrow = section.eyebrow,
                    title = section.title,
                    subtitle = section.subtitle,
                    displayStyle = section.displayStyle.name,
                    desktopColumns = section.desktopColumns,
                    mobileColumns = section.mobileColumns,
                    linkLabel = section.linkLabel,
                    linkUrl = section.linkUrl,
                    groups = section.groups.map { group ->
                        GroupData(
                            id = group.id,
                            code = group.code,
                            title = group.title,
                            selectionMode = group.selectionMode.name,
                            strategy = group.strategy.name,
                            products = group.products.map { item ->
                                val product = item.product
                                ProductData(
                                    id = product.id,
                                    productType = product.productType,
                                    categoryId = product.categoryId,
                                    name = product.name,
                                    status = product.status.name,
                                    highlights = product.highlights.toList(),
                                    materials = product.materials.map { MaterialData(it.name, it.percentage.toPlainString()) },
                                    images = product.images.map { image ->
                                        ImageData(image.url, image.altText, image.primary, image.sortOrder)
                                    },
                                    attributes = product.attributes.map { AttributeData(it.code, it.value) },
                                    fitSense = product.fitSense,
                                    description = product.description,
                                    designAndExtras = product.designAndExtras.toList(),
                                    careInstructions = product.careInstructions.toList(),
                                    tags = product.tags.toList(),
                                    score = product.score,
                                    variants = product.variants
                                        .filter {
                                            it.status == ProductVariant.Status.ACTIVE &&
                                                it.warehouseVolume >= group.minimumStock && it.price.signum() > 0
                                        }
                                        .sortedWith(
                                            compareBy(
                                                HomeRecommendationService.ProductVariantData::displayOrder,
                                                HomeRecommendationService.ProductVariantData::id,
                                            ),
                                        )
                                        .map { variant ->
                                            VariantData(
                                                id = variant.id,
                                                sku = variant.sku,
                                                size = variant.size,
                                                color = variant.color,
                                                price = variant.price.toPlainString(),
                                                currency = "USD",
                                                warehouseVolume = variant.warehouseVolume,
                                                salesVolume = variant.salesVolume,
                                                displayOrder = variant.displayOrder,
                                                attributes = variant.attributes.map { attribute ->
                                                    AttributeData(attribute.code, attribute.value)
                                                },
                                            )
                                        },
                                    createdAt = product.createdAt,
                                    updatedAt = product.updatedAt,
                                    badge = item.badge,
                                    recommendationContext = RecommendationContextData(
                                        requestId = item.context.requestId,
                                        planId = item.context.planId,
                                        sectionCode = item.context.sectionCode,
                                        groupCode = item.context.groupCode,
                                        strategy = item.context.strategy.name,
                                        position = item.context.position,
                                    ),
                                )
                            },
                        )
                    },
                )
            },
        )
        return builder.ok()
            .header("Cache-Control", "public, max-age=30, stale-while-revalidate=60")
            .data(rs)
            .build()
    }
}
