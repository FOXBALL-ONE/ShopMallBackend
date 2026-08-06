package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminProductService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 商品/统一管理
 */
@Validated
@RestController
@RequestMapping("/admin/api/products")
class AdminProductController(
    private val adminProductService: AdminProductService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 分页查询全部商品
     */
    @GetMapping
    fun getProducts(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("product_type", required = false) productType: AdminProductService.ProductType?,
        @RequestParam("status", required = false) status: Product.Status?,
        @RequestParam("keyword", required = false) @Size(max = 200) keyword: String?,
        @RequestParam("low_stock", defaultValue = "false") lowStock: Boolean,
        @RequestParam("low_stock_threshold", defaultValue = "10") @Min(0) @Max(1_000_000) lowStockThreshold: Int,
        @RequestParam("sort_by", defaultValue = "UPDATED_AT") sortBy: AdminProductService.SortBy,
        @RequestParam("ascending", defaultValue = "false") ascending: Boolean,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
            val active: Boolean,
        )

        data class ProductData(
            val id: Long,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            @param:JsonProperty("sales_volume")
            val salesVolume: Int,
            val status: String,
            val highlight: List<String>,
            val images: List<String>,
            @param:JsonProperty("fit_sense")
            val fitSense: String?,
            val description: String?,
            @param:JsonProperty("design_and_extras")
            val designAndExtras: List<String>,
            @param:JsonProperty("care_instructions")
            val careInstructions: List<String>,
            val score: Float?,
            val tags: List<TagData>,
            val size: String?,
            val length: String?,
            val silhouette: String?,
            val neckline: String?,
            @param:JsonProperty("sleeve_type")
            val sleeveType: String?,
            val fabric: String?,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("support_level")
            val supportLevel: String?,
            val coverage: String?,
            @param:JsonProperty("torso_fit")
            val torsoFit: String?,
            @param:JsonProperty("back_style")
            val backStyle: String?,
            @param:JsonProperty("tummy_control")
            val tummyControl: Boolean?,
            @param:JsonProperty("removable_padding")
            val removablePadding: Boolean?,
            val style: String?,
            @param:JsonProperty("sheer_level")
            val sheerLevel: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Pagination(
            val page: Int,
            val size: Int,
            @param:JsonProperty("total_items")
            val totalItems: Long,
            @param:JsonProperty("total_pages")
            val totalPages: Int,
        )

        data class Response(val list: List<ProductData>, val pagination: Pagination)

        adminAccessService.requireAdmin(adminId)
        val products = adminProductService.list(
            productType = productType,
            status = status,
            keyword = keyword,
            lowStock = lowStock,
            lowStockThreshold = lowStockThreshold,
            sortBy = sortBy,
            ascending = ascending,
            page = page - 1,
            size = size,
        )
        val list = products.content.map { product ->
            val type = when (product) {
                is BikiniSuit -> AdminProductService.ProductType.BIKINI
                is OnePieceSuit -> AdminProductService.ProductType.ONE_PIECE
                is Dress -> AdminProductService.ProductType.DRESS
                is CoverUp -> AdminProductService.ProductType.COVER_UP
                else -> error("Unsupported product type: ${product::class.qualifiedName}")
            }
            val tags = product.tags
                .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                .map {
                    TagData(
                        id = requireNotNull(it.id),
                        name = it.name,
                        description = it.description,
                        color = it.color,
                        sortOrder = it.sortOrder,
                        active = it.active,
                    )
                }
            ProductData(
                id = requireNotNull(product.id),
                productType = type.name,
                name = product.name,
                color = product.color,
                price = product.price,
                warehouseVolume = product.warehouseVolume,
                salesVolume = product.salesVolume,
                status = product.status.name,
                highlight = product.highlight.toList(),
                images = product.images.toList(),
                fitSense = product.fitSense,
                description = product.description,
                designAndExtras = product.designAndExtras.toList(),
                careInstructions = product.careInstructions.toList(),
                score = product.score,
                tags = tags,
                size = when (product) {
                    is Dress -> product.size?.name
                    is OnePieceSuit -> product.size?.name
                    is CoverUp -> product.size.name
                    else -> null
                },
                length = (product as? Dress)?.length?.name,
                silhouette = (product as? Dress)?.silhouette?.name,
                neckline = when (product) {
                    is Dress -> product.neckline?.name
                    is OnePieceSuit -> product.neckline?.name
                    else -> null
                },
                sleeveType = (product as? Dress)?.sleeveType?.name,
                fabric = when (product) {
                    is Dress -> product.fabric
                    is CoverUp -> product.fabric
                    else -> null
                },
                topSize = (product as? BikiniSuit)?.topSize?.name,
                bottomSize = (product as? BikiniSuit)?.bottomSize?.name,
                supportLevel = (product as? OnePieceSuit)?.supportLevel?.name,
                coverage = (product as? OnePieceSuit)?.coverage?.name,
                torsoFit = (product as? OnePieceSuit)?.torsoFit?.name,
                backStyle = (product as? OnePieceSuit)?.backStyle?.name,
                tummyControl = (product as? OnePieceSuit)?.tummyControl,
                removablePadding = (product as? OnePieceSuit)?.removablePadding,
                style = (product as? CoverUp)?.style?.name,
                sheerLevel = (product as? CoverUp)?.sheerLevel?.name,
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
            )
        }
        val rs = Response(
            list = list,
            pagination = Pagination(page, size, products.totalElements, products.totalPages),
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 修改商品上下架状态
     */
    @PatchMapping("/{id}/status")
    fun updateStatus(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("status") status: Product.Status,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val status: String)

        adminAccessService.requireAdmin(adminId)
        val updatedStatus = adminProductService.updateStatus(id, status) ?: return builder.notFound().build()
        val rs = Response(id, updatedStatus.name)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 调整商品库存
     */
    @PatchMapping("/{id}/stock")
    fun adjustStock(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("adjustment") @Min(-1_000_000) @Max(1_000_000) adjustment: Int,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val adjustment: Int,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
        )

        adminAccessService.requireAdmin(adminId)
        val warehouseVolume = adminProductService.adjustStock(id, adjustment) ?: return builder.notFound().build()
        val rs = Response(id, adjustment, warehouseVolume)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量修改商品上下架状态
     */
    @PostMapping("/batch/status")
    fun updateStatuses(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
        @RequestParam("status") status: Product.Status,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val status: String, val updated: Int)

        adminAccessService.requireAdmin(adminId)
        val orderedIds = ids.sorted()
        val updated = adminProductService.updateStatuses(orderedIds, status)
        val rs = Response(orderedIds, status.name, updated)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量软删除商品
     */
    @DeleteMapping("/batch")
    fun deleteProducts(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val deleted: Int)

        adminAccessService.requireAdmin(adminId)
        val orderedIds = ids.sorted()
        val deleted = adminProductService.softDelete(orderedIds)
        val rs = Response(orderedIds, deleted)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量永久删除已逻辑删除商品
     */
    @DeleteMapping("/batch/permanent")
    fun permanentlyDeleteProducts(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val deleted: Int)

        adminAccessService.requireAdmin(adminId)
        val orderedIds = ids.sorted()
        val deleted = adminProductService.permanentlyDelete(orderedIds)
        val rs = Response(orderedIds, deleted)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 恢复单个已删除商品
     */
    @PostMapping("/{id}/restore")
    fun restoreProduct(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val status: String)

        adminAccessService.requireAdmin(adminId)
        val status = adminProductService.restore(id) ?: return builder.notFound().build()
        val rs = Response(id, status.name)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量恢复已删除商品
     */
    @PostMapping("/batch/restore")
    fun restoreProducts(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val status: String, val restored: Int)

        adminAccessService.requireAdmin(adminId)
        val orderedIds = ids.sorted()
        val restored = adminProductService.restore(orderedIds)
        val rs = Response(orderedIds, Product.Status.INACTIVE.name, restored)
        return builder.ok().data(rs).build()
    }
}
