package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminProductService
import top.foxball.shopmall.service.CreateProductCommand
import top.foxball.shopmall.service.ProductAttributeInput
import top.foxball.shopmall.service.ProductImageInput
import top.foxball.shopmall.service.ProductMaterialInput
import top.foxball.shopmall.service.ProductService
import top.foxball.shopmall.service.ProductVariantInput
import top.foxball.shopmall.service.ProductVariantService
import top.foxball.shopmall.service.UpdateProductCommand
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

@Validated
@RestController
@RequestMapping("/admin/api/products")
class AdminProductController(
    private val adminProductService: AdminProductService,
    private val productService: ProductService,
    private val productVariantService: ProductVariantService,
    private val adminAccessService: AdminAccessService,
    private val objectMapper: ObjectMapper,
    private val builder: ResponseBuilder,
) {
    @GetMapping
    fun getProducts(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("product_type", required = false) @Size(max = 64) productType: String?,
        @RequestParam("status", required = false) status: Product.Status?,
        @RequestParam("deleted", required = false) deleted: Boolean?,
        @RequestParam("keyword", required = false) @Size(max = 200) keyword: String?,
        @RequestParam("low_stock", defaultValue = "false") lowStock: Boolean,
        @RequestParam("low_stock_threshold", defaultValue = "10") @Min(0) @Max(1_000_000) lowStockThreshold: Int,
        @RequestParam("sort_by", defaultValue = "UPDATED_AT") sortBy: AdminProductService.SortBy,
        @RequestParam("ascending", defaultValue = "false") ascending: Boolean,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<Response> {
        data class AttributeData(val code: String, val value: String)
        data class MaterialData(val name: String, val percentage: BigDecimal)
        data class ImageData(val url: String, @param:JsonProperty("alt_text") val altText: String?, @param:JsonProperty("is_primary") val primary: Boolean, @param:JsonProperty("sort_order") val sortOrder: Int)
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
            val status: String,
            @param:JsonProperty("option_signature") val optionSignature: String,
            val attributes: List<AttributeData>,
        )
        data class ProductData(
            val id: Long,
            @param:JsonProperty("product_type") val productType: String,
            @param:JsonProperty("product_type_id") val productTypeId: Long,
            @param:JsonProperty("category_id") val categoryId: Long?,
            val name: String,
            val status: String,
            @param:JsonProperty("deleted_at") val deletedAt: LocalDateTime?,
            val attributes: List<AttributeData>,
            val highlights: List<String>,
            val materials: List<MaterialData>,
            val images: List<ImageData>,
            @param:JsonProperty("fit_sense") val fitSense: String?,
            val description: String?,
            @param:JsonProperty("design_and_extras") val designAndExtras: List<String>,
            @param:JsonProperty("care_instructions") val careInstructions: List<String>,
            @param:JsonProperty("tag_ids") val tagIds: List<Long>,
            val variants: List<VariantData>,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Pagination(val page: Int, val size: Int, @param:JsonProperty("total_items") val totalItems: Long, @param:JsonProperty("total_pages") val totalPages: Int)
        data class Response(val list: List<ProductData>, val pagination: Pagination)

        adminAccessService.requireAdmin(adminId)
        val products = adminProductService.list(productType, status, deleted, keyword, lowStock, lowStockThreshold, sortBy, ascending, page - 1, size)
        val list = products.content.map { product ->
            ProductData(
                id = requireNotNull(product.id),
                productType = requireNotNull(product.productType).code,
                productTypeId = requireNotNull(product.productType?.id),
                categoryId = product.category?.id,
                name = product.name,
                status = product.status.name,
                deletedAt = product.deletedAt,
                attributes = product.attributes.map { AttributeData(it.code, it.value) },
                highlights = product.highlights.toList(),
                materials = product.materials.map { MaterialData(it.name, it.percentage) },
                images = product.images.mapIndexed { index, image -> ImageData(image.url, image.altText, image.primary, index) },
                fitSense = product.fitSense,
                description = product.description,
                designAndExtras = product.designAndExtras.toList(),
                careInstructions = product.careInstructions.map { it.text },
                tagIds = product.tags.mapNotNull { it.id }.sorted(),
                variants = product.variants.sortedWith(compareBy(ProductVariant::displayOrder, ProductVariant::id)).map { variant ->
                    VariantData(
                        id = requireNotNull(variant.id),
                        sku = variant.sku,
                        size = variant.size,
                        color = variant.color,
                        price = variant.price.toPlainString(),
                        currency = "USD",
                        warehouseVolume = variant.warehouseVolume,
                        salesVolume = variant.salesVolume,
                        displayOrder = variant.displayOrder,
                        status = variant.status.name,
                        optionSignature = variant.optionSignature,
                        attributes = variant.attributes.map { AttributeData(it.code, it.value) },
                    )
                },
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
            )
        }
        val rs = Response(list, Pagination(page, size, products.totalElements, products.totalPages))
        return builder.ok().data(rs).build()
    }

    @GetMapping("/{id}")
    fun getProduct(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
    ): ResponseEntity<Response> {
        data class AttributeData(val code: String, val value: String)
        data class MaterialData(val name: String, val percentage: BigDecimal)
        data class ImageData(val url: String, @param:JsonProperty("alt_text") val altText: String?, @param:JsonProperty("is_primary") val primary: Boolean, @param:JsonProperty("sort_order") val sortOrder: Int)
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
            val status: String,
            val attributes: List<AttributeData>,
        )
        data class Response(
            val id: Long,
            @param:JsonProperty("product_type") val productType: String,
            @param:JsonProperty("product_type_id") val productTypeId: Long,
            @param:JsonProperty("category_id") val categoryId: Long?,
            val name: String,
            val status: String,
            @param:JsonProperty("deleted_at") val deletedAt: LocalDateTime?,
            val attributes: List<AttributeData>,
            val highlights: List<String>,
            val materials: List<MaterialData>,
            val images: List<ImageData>,
            @param:JsonProperty("fit_sense") val fitSense: String?,
            val description: String?,
            @param:JsonProperty("design_and_extras") val designAndExtras: List<String>,
            @param:JsonProperty("care_instructions") val careInstructions: List<String>,
            @param:JsonProperty("tag_ids") val tagIds: List<Long>,
            val variants: List<VariantData>,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val product = productService.getAdmin(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(product.id),
            productType = requireNotNull(product.productType).code,
            productTypeId = requireNotNull(product.productType?.id),
            categoryId = product.category?.id,
            name = product.name,
            status = product.status.name,
            deletedAt = product.deletedAt,
            attributes = product.attributes.map { AttributeData(it.code, it.value) },
            highlights = product.highlights.toList(),
            materials = product.materials.map { MaterialData(it.name, it.percentage) },
            images = product.images.mapIndexed { index, image -> ImageData(image.url, image.altText, image.primary, index) },
            fitSense = product.fitSense,
            description = product.description,
            designAndExtras = product.designAndExtras.toList(),
            careInstructions = product.careInstructions.map { it.text },
            tagIds = product.tags.mapNotNull { it.id }.sorted(),
            variants = product.variants.sortedWith(compareBy(ProductVariant::displayOrder, ProductVariant::id)).map { variant ->
                VariantData(
                    id = requireNotNull(variant.id),
                    sku = variant.sku,
                    size = variant.size,
                    color = variant.color,
                    price = variant.price.toPlainString(),
                    currency = "USD",
                    warehouseVolume = variant.warehouseVolume,
                    salesVolume = variant.salesVolume,
                    displayOrder = variant.displayOrder,
                    status = variant.status.name,
                    attributes = variant.attributes.map { AttributeData(it.code, it.value) },
                )
            },
            createdAt = product.createdAt,
            updatedAt = product.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createProduct(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("product_type_id") @Min(1) productTypeId: Long,
        @RequestParam("category_id", required = false) @Min(1) categoryId: Long?,
        @RequestParam("name") @Size(min = 1, max = 200) name: String,
        @RequestParam("status", defaultValue = "INACTIVE") status: Product.Status,
        @RequestParam("highlights", defaultValue = "[]") highlights: String,
        @RequestParam("materials", defaultValue = "[]") materials: String,
        @RequestParam("attributes", defaultValue = "[]") attributes: String,
        @RequestParam("images", defaultValue = "[]") images: String,
        @RequestParam("fit_sense", required = false) @Size(max = 255) fitSense: String?,
        @RequestParam("description", required = false) @Size(max = 4_000) description: String?,
        @RequestParam("design_and_extras", defaultValue = "[]") designAndExtras: String,
        @RequestParam("care_instructions", defaultValue = "[]") careInstructions: String,
        @RequestParam("tag_ids", defaultValue = "[]") tagIds: String,
        @RequestParam("variants") variants: String,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("created_at") val createdAt: LocalDateTime?)

        adminAccessService.requireAdmin(adminId)
        val product = productService.create(
            CreateProductCommand(
                productTypeId = productTypeId,
                categoryId = categoryId,
                name = name,
                status = status,
                highlights = objectMapper.readValue<List<String>>(highlights),
                materials = objectMapper.readValue<List<ProductMaterialInput>>(materials),
                attributes = objectMapper.readValue<List<ProductAttributeInput>>(attributes),
                images = objectMapper.readValue<List<ProductImageInput>>(images),
                fitSense = fitSense,
                description = description,
                designAndExtras = objectMapper.readValue<List<String>>(designAndExtras),
                careInstructions = objectMapper.readValue<List<String>>(careInstructions),
                tagIds = objectMapper.readValue<Set<Long>>(tagIds),
                variants = objectMapper.readValue<List<ProductVariantInput>>(variants),
            ),
        )
        val rs = Response(requireNotNull(product.id), product.createdAt)
        return builder.created().data(rs).build()
    }

    @PutMapping("/{id}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateProduct(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
        @RequestParam("category_id", required = false) @Min(1) categoryId: Long?,
        @RequestParam("name") @Size(min = 1, max = 200) name: String,
        @RequestParam("status") status: Product.Status,
        @RequestParam("highlights", defaultValue = "[]") highlights: String,
        @RequestParam("materials", defaultValue = "[]") materials: String,
        @RequestParam("attributes", defaultValue = "[]") attributes: String,
        @RequestParam("images", defaultValue = "[]") images: String,
        @RequestParam("fit_sense", required = false) @Size(max = 255) fitSense: String?,
        @RequestParam("description", required = false) @Size(max = 4_000) description: String?,
        @RequestParam("design_and_extras", defaultValue = "[]") designAndExtras: String,
        @RequestParam("care_instructions", defaultValue = "[]") careInstructions: String,
        @RequestParam("tag_ids", defaultValue = "[]") tagIds: String,
        @RequestParam("variants") variants: String,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?)

        adminAccessService.requireAdmin(adminId)
        val product = productService.update(
            id,
            UpdateProductCommand(
                categoryId = categoryId,
                name = name,
                status = status,
                highlights = objectMapper.readValue<List<String>>(highlights),
                materials = objectMapper.readValue<List<ProductMaterialInput>>(materials),
                attributes = objectMapper.readValue<List<ProductAttributeInput>>(attributes),
                images = objectMapper.readValue<List<ProductImageInput>>(images),
                fitSense = fitSense,
                description = description,
                designAndExtras = objectMapper.readValue<List<String>>(designAndExtras),
                careInstructions = objectMapper.readValue<List<String>>(careInstructions),
                tagIds = objectMapper.readValue<Set<Long>>(tagIds),
                variants = objectMapper.readValue<List<ProductVariantInput>>(variants),
            ),
        ) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(product.id), product.updatedAt)
        return builder.ok().data(rs).build()
    }

    @GetMapping("/{product_id}/variants")
    fun listVariants(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("product_id") @Min(1) productId: Long,
    ): ResponseEntity<Response> {
        data class AttributeData(val code: String, val value: String)
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
            val status: String,
            @param:JsonProperty("option_signature") val optionSignature: String,
            val attributes: List<AttributeData>,
        )
        data class Response(val list: List<VariantData>)

        adminAccessService.requireAdmin(adminId)
        if (productService.getAdmin(productId) == null) return builder.notFound().build()
        val rs = Response(
            productVariantService.list(productId).map {
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
                    status = it.status.name,
                    optionSignature = it.optionSignature,
                    attributes = it.attributes.map { attribute -> AttributeData(attribute.code, attribute.value) },
                )
            },
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/{product_id}/variants", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createVariant(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("product_id") @Min(1) productId: Long,
        @RequestParam("sku") @Size(min = 1, max = 64) sku: String,
        @RequestParam("size", required = false) @Size(max = 30) size: String?,
        @RequestParam("color") @Size(min = 1, max = 50) color: String,
        @RequestParam("price") @DecimalMin(value = "0.00", inclusive = false) price: BigDecimal,
        @RequestParam("warehouse_volume", defaultValue = "0") @Min(0) warehouseVolume: Int,
        @RequestParam("status", defaultValue = "INACTIVE") status: ProductVariant.Status,
        @RequestParam("display_order", defaultValue = "0") @Min(0) displayOrder: Int,
        @RequestParam("attributes", defaultValue = "[]") attributes: String,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("variant_id") val variantId: Long, val sku: String)

        adminAccessService.requireAdmin(adminId)
        val variant = productVariantService.create(
            productId,
            ProductVariantInput(
                sku,
                size,
                color,
                price,
                warehouseVolume,
                status,
                displayOrder,
                objectMapper.readValue<List<ProductAttributeInput>>(attributes),
            ),
        ) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(variant.id), variant.sku)
        return builder.created().data(rs).build()
    }

    @PutMapping("/variants/{variant_id}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateVariant(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("variant_id") @Min(1) variantId: Long,
        @RequestParam("sku") @Size(min = 1, max = 64) sku: String,
        @RequestParam("size", required = false) @Size(max = 30) size: String?,
        @RequestParam("color") @Size(min = 1, max = 50) color: String,
        @RequestParam("price") @DecimalMin(value = "0.00", inclusive = false) price: BigDecimal,
        @RequestParam("warehouse_volume", defaultValue = "0") @Min(0) warehouseVolume: Int,
        @RequestParam("status") status: ProductVariant.Status,
        @RequestParam("display_order", defaultValue = "0") @Min(0) displayOrder: Int,
        @RequestParam("attributes", defaultValue = "[]") attributes: String,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("variant_id") val variantId: Long, val status: String)

        adminAccessService.requireAdmin(adminId)
        val variant = productVariantService.update(
            variantId,
            ProductVariantInput(
                sku,
                size,
                color,
                price,
                warehouseVolume,
                status,
                displayOrder,
                objectMapper.readValue<List<ProductAttributeInput>>(attributes),
            ),
        ) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(variant.id), variant.status.name)
        return builder.ok().data(rs).build()
    }

    @PatchMapping("/variants/{variant_id}/status")
    fun updateVariantStatus(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("variant_id") @Min(1) variantId: Long,
        @RequestParam("status") status: ProductVariant.Status,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("variant_id") val variantId: Long, val status: String)

        adminAccessService.requireAdmin(adminId)
        val variant = productVariantService.updateStatus(variantId, status) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(variant.id), variant.status.name)
        return builder.ok().data(rs).build()
    }

    @DeleteMapping("/variants/{variant_id}")
    fun deleteVariant(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("variant_id") @Min(1) variantId: Long,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("variant_id") val variantId: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!productVariantService.delete(variantId)) return builder.notFound().build()
        return builder.ok().data(Response(variantId, true)).build()
    }

    @PatchMapping("/{id}/status")
    fun updateStatus(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
        @RequestParam("status") status: Product.Status,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val status: String)

        adminAccessService.requireAdmin(adminId)
        val updated = adminProductService.updateStatus(id, status) ?: return builder.notFound().build()
        return builder.ok().data(Response(id, updated.name)).build()
    }

    @PatchMapping("/variants/{variant_id}/stock")
    fun adjustVariantStock(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("variant_id") @Min(1) variantId: Long,
        @RequestParam("adjustment") @Min(-1_000_000) @Max(1_000_000) adjustment: Int,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("variant_id") val variantId: Long, val adjustment: Int, @param:JsonProperty("warehouse_volume") val warehouseVolume: Int)

        adminAccessService.requireAdmin(adminId)
        val warehouseVolume = adminProductService.adjustStock(variantId, adjustment) ?: return builder.notFound().build()
        return builder.ok().data(Response(variantId, adjustment, warehouseVolume)).build()
    }

    @PatchMapping("/variants/batch/status")
    fun updateVariantStatuses(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("variant_ids") @Size(min = 1, max = 100) variantIds: Set<Long>,
        @RequestParam("status") status: ProductVariant.Status,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("variant_ids") val variantIds: List<Long>, val status: String, val updated: Int)

        adminAccessService.requireAdmin(adminId)
        val ordered = variantIds.sorted()
        return builder.ok().data(Response(ordered, status.name, productVariantService.updateStatuses(ordered, status))).build()
    }

    @PatchMapping("/variants/batch/stock")
    fun adjustVariantStocks(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("variant_ids") @Size(min = 1, max = 100) variantIds: Set<Long>,
        @RequestParam("adjustment") @Min(-1_000_000) @Max(1_000_000) adjustment: Int,
    ): ResponseEntity<Response> {
        data class StockData(@param:JsonProperty("variant_id") val variantId: Long, @param:JsonProperty("warehouse_volume") val warehouseVolume: Int)
        data class Response(val adjustment: Int, val list: List<StockData>)

        adminAccessService.requireAdmin(adminId)
        val stocks = adminProductService.adjustStocks(variantIds.sorted(), adjustment)
        val list = stocks.map { (variantId, warehouseVolume) -> StockData(variantId, warehouseVolume) }
        return builder.ok().data(Response(adjustment, list)).build()
    }

    @PostMapping("/batch/status")
    fun updateStatuses(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
        @RequestParam("status") status: Product.Status,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val status: String, val updated: Int)

        adminAccessService.requireAdmin(adminId)
        val ordered = ids.sorted()
        return builder.ok().data(Response(ordered, status.name, adminProductService.updateStatuses(ordered, status))).build()
    }

    @DeleteMapping("/batch")
    fun deleteProducts(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val deleted: Int)

        adminAccessService.requireAdmin(adminId)
        val ordered = ids.sorted()
        return builder.ok().data(Response(ordered, adminProductService.softDelete(ordered))).build()
    }

    @DeleteMapping("/batch/permanent")
    fun permanentlyDeleteProducts(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val deleted: Int)

        adminAccessService.requireAdmin(adminId)
        val ordered = ids.sorted()
        return builder.ok().data(Response(ordered, adminProductService.permanentlyDelete(ordered))).build()
    }

    @PostMapping("/{id}/restore")
    fun restoreProduct(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val status: String)

        adminAccessService.requireAdmin(adminId)
        val status = adminProductService.restore(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(id, status.name)).build()
    }
}
