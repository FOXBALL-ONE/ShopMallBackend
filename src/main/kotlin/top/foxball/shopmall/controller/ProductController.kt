package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.service.ProductService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/** 统一客户商品 API。所有加入购物车与下单操作均以 variant_id 为准。 */
@Validated
@RestController
class ProductController(
    private val productService: ProductService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/products")
    fun getPublishedProducts(
        @RequestParam("product_type", required = false) @Size(max = 64) productType: String?,
        @RequestParam("category_id", required = false) @Min(1) categoryId: Long?,
        @RequestParam("keyword", required = false) @Size(max = 200) keyword: String?,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "24") @Min(1) @jakarta.validation.constraints.Max(100) size: Int,
    ): ResponseEntity<Response> {
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
        )
        data class Pagination(val page: Int, val size: Int, @param:JsonProperty("total_items") val totalItems: Long, @param:JsonProperty("total_pages") val totalPages: Int)
        data class Response(val list: List<ProductData>, val pagination: Pagination)

        val products = productService.listPublished(productType, categoryId, keyword, page - 1, size)
        val list = products.content.map { product ->
            ProductData(
                id = requireNotNull(product.id),
                productType = requireNotNull(product.productType).code,
                categoryId = product.category?.id,
                name = product.name,
                status = product.status.name,
                highlights = product.highlights.toList(),
                materials = product.materials.map { MaterialData(it.name, it.percentage.toPlainString()) },
                images = product.images.mapIndexed { index, image -> ImageData(image.url, image.altText, image.primary, index) },
                attributes = product.attributes.map { AttributeData(it.code, it.value) },
                fitSense = product.fitSense,
                description = product.description,
                designAndExtras = product.designAndExtras.toList(),
                careInstructions = product.careInstructions.map { it.text },
                tags = product.tags.map { it.name }.sorted(),
                score = product.score,
                variants = product.variants
                    .filter { it.status == ProductVariant.Status.ACTIVE }
                    .map {
                        VariantData(
                            id = requireNotNull(it.id),
                            sku = it.sku,
                            size = it.size,
                            color = it.color,
                            price = it.price.toPlainString(),
                            currency = "USD",
                            warehouseVolume = it.warehouseVolume,
                            salesVolume = it.salesVolume,
                            displayOrder = it.displayOrder,
                            attributes = it.attributes.map { attribute -> AttributeData(attribute.code, attribute.value) },
                        )
                    },
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
            )
        }
        return builder.ok().data(Response(list, Pagination(page, size, products.totalElements, products.totalPages))).build()
    }

    @GetMapping("/api/products/{id}")
    fun getPublishedProduct(@PathVariable("id") @Min(1) id: Long): ResponseEntity<Response> {
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
        data class Response(
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
        )

        val product = productService.getPublished(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(product.id),
            productType = requireNotNull(product.productType).code,
            categoryId = product.category?.id,
            name = product.name,
            status = product.status.name,
            highlights = product.highlights.toList(),
            materials = product.materials.map { MaterialData(it.name, it.percentage.toPlainString()) },
            images = product.images.mapIndexed { index, image -> ImageData(image.url, image.altText, image.primary, index) },
            attributes = product.attributes.map { AttributeData(it.code, it.value) },
            fitSense = product.fitSense,
            description = product.description,
            designAndExtras = product.designAndExtras.toList(),
            careInstructions = product.careInstructions.map { it.text },
            tags = product.tags.map { it.name }.sorted(),
            score = product.score,
            variants = product.variants.filter { it.status == ProductVariant.Status.ACTIVE }.map {
                VariantData(
                    id = requireNotNull(it.id),
                    sku = it.sku,
                    size = it.size,
                    color = it.color,
                    price = it.price.toPlainString(),
                    currency = "USD",
                    warehouseVolume = it.warehouseVolume,
                    salesVolume = it.salesVolume,
                    displayOrder = it.displayOrder,
                    attributes = it.attributes.map { attribute -> AttributeData(attribute.code, attribute.value) },
                )
            },
            createdAt = product.createdAt,
            updatedAt = product.updatedAt,
        )
        return builder.ok().data(rs).build()
    }
}
