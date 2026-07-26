package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.SizeRecommendation
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.DressService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 商品/连衣裙
 */
@Validated
@RestController
class DressController(
    private val dressService: DressService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取已上架连衣裙列表
     */
    @GetMapping("/api/dresses")
    fun getPublishedDresses(): ResponseEntity<Response> {
        data class DressData(
            val id: Long,
            val name: String,
            val size: String,
            @param:JsonProperty("size_recommendation")
            val sizeRecommendation: SizeRecommendation?,
            val length: String?,
            val silhouette: String?,
            val neckline: String?,
            @param:JsonProperty("sleeve_type")
            val sleeveType: String?,
            val fabric: String?,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            @param:JsonProperty("sales_volume")
            val salesVolume: Int,
            val status: String,
            val images: List<String>,
            val highlight: List<String>,
            val description: String?,
            val score: Float?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
        )

        data class Response(val list: List<DressData>)

        val list = dressService.listPublished().map { dress ->
            val size = requireNotNull(dress.size)
            DressData(
                id = requireNotNull(dress.id),
                name = dress.name,
                size = size.name,
                sizeRecommendation = size.recommendation,
                length = dress.length?.name,
                silhouette = dress.silhouette?.name,
                neckline = dress.neckline?.name,
                sleeveType = dress.sleeveType?.name,
                fabric = dress.fabric,
                color = dress.color,
                price = dress.price,
                warehouseVolume = dress.warehouseVolume,
                salesVolume = dress.salesVolume,
                status = dress.status.name,
                images = dress.images.toList(),
                highlight = dress.highlight.toList(),
                description = dress.description,
                score = dress.score,
                createdAt = dress.createdAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取已上架连衣裙
     * @param id 商品 ID
     */
    @GetMapping("/api/dresses/{id}")
    fun getPublishedDress(
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class TagData(val id: Long, val name: String, val color: String?)

        data class Response(
            val id: Long,
            val name: String,
            val size: String,
            @param:JsonProperty("size_recommendation")
            val sizeRecommendation: SizeRecommendation?,
            val length: String?,
            val silhouette: String?,
            val neckline: String?,
            @param:JsonProperty("sleeve_type")
            val sleeveType: String?,
            val fabric: String?,
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
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val dress = dressService.getPublished(id) ?: return builder.notFound().build()
        val size = requireNotNull(dress.size)
        val rs = Response(
            id = requireNotNull(dress.id),
            name = dress.name,
            size = size.name,
            sizeRecommendation = size.recommendation,
            length = dress.length?.name,
            silhouette = dress.silhouette?.name,
            neckline = dress.neckline?.name,
            sleeveType = dress.sleeveType?.name,
            fabric = dress.fabric,
            color = dress.color,
            price = dress.price,
            warehouseVolume = dress.warehouseVolume,
            salesVolume = dress.salesVolume,
            status = dress.status.name,
            highlight = dress.highlight.toList(),
            images = dress.images.toList(),
            fitSense = dress.fitSense,
            description = dress.description,
            designAndExtras = dress.designAndExtras.toList(),
            careInstructions = dress.careInstructions.toList(),
            score = dress.score,
            tags = dress.tags.sortedBy { it.sortOrder }.map { TagData(requireNotNull(it.id), it.name, it.color) },
            createdAt = dress.createdAt,
            updatedAt = dress.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端连衣裙列表
     */
    @GetMapping("/api/admin/dresses")
    fun getAdminDresses(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class DressData(
            val id: Long,
            val name: String,
            val size: String,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            val status: String,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<DressData>)

        adminAccessService.requireAdmin(adminId)
        val list = dressService.listForAdmin().map {
            DressData(
                id = requireNotNull(it.id),
                name = it.name,
                size = requireNotNull(it.size).name,
                color = it.color,
                price = it.price,
                warehouseVolume = it.warehouseVolume,
                status = it.status.name,
                updatedAt = it.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端连衣裙
     * @param id 商品 ID
     */
    @GetMapping("/api/admin/dresses/{id}")
    fun getAdminDress(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val size: String,
            val length: String?,
            val silhouette: String?,
            val neckline: String?,
            @param:JsonProperty("sleeve_type")
            val sleeveType: String?,
            val fabric: String?,
            @param:JsonProperty("tag_ids")
            val tagIds: List<Long>,
        )

        adminAccessService.requireAdmin(adminId)
        val dress = dressService.getForAdmin(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(dress.id),
            name = dress.name,
            size = requireNotNull(dress.size).name,
            length = dress.length?.name,
            silhouette = dress.silhouette?.name,
            neckline = dress.neckline?.name,
            sleeveType = dress.sleeveType?.name,
            fabric = dress.fabric,
            tagIds = dress.tags.mapNotNull { it.id },
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 创建连衣裙
     * @param name 商品名称
     * @param size 尺码
     * @param length 长度
     * @param silhouette 廓形
     * @param neckline 领口
     * @param sleeveType 袖型
     * @param fabric 面料
     * @param color 颜色
     * @param price 价格
     * @param warehouseVolume 库存数量
     * @param status 商品状态
     * @param images 商品图片
     * @param highlight 商品卖点
     * @param description 商品描述
     * @param tagIds 标签 ID
     */
    @PostMapping("/api/admin/dresses")
    fun createDress(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("size") size: Dress.Size,
        @RequestParam("length", required = false) length: Dress.Length?,
        @RequestParam("silhouette", required = false) silhouette: Dress.Silhouette?,
        @RequestParam("neckline", required = false) neckline: Dress.Neckline?,
        @RequestParam("sleeve_type", required = false) sleeveType: Dress.SleeveType?,
        @RequestParam("fabric", required = false) @Size(max = 100) fabric: String?,
        @RequestParam("color") @NotBlank @Size(max = 50) color: String,
        @RequestParam("price") @DecimalMin("0.01") price: BigDecimal,
        @RequestParam("warehouse_volume", defaultValue = "0") @Min(0) warehouseVolume: Int,
        @RequestParam("status", defaultValue = "ACTIVE") status: Product.Status,
        @RequestParam("images", required = false) @Size(max = 12) images: List<String>?,
        @RequestParam("highlight", required = false) @Size(max = 10) highlight: List<String>?,
        @RequestParam("description", required = false) @Size(max = 4000) description: String?,
        @RequestParam("tag_ids", required = false) @Size(max = 20) tagIds: Set<Long>?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, val status: String)

        adminAccessService.requireAdmin(adminId)
        val source = Dress(size, length, silhouette, neckline, sleeveType, fabric).apply {
            this.name = name
            this.color = color
            this.price = price
            this.warehouseVolume = warehouseVolume
            this.status = status
            this.images = images.orEmpty().toMutableList()
            this.highlight = highlight.orEmpty().toMutableList()
            this.description = description
        }
        val dress = dressService.create(source, tagIds.orEmpty())
        val rs = Response(requireNotNull(dress.id), dress.name, dress.status.name)
        return builder.status(HttpStatus.CREATED).data(rs).build()
    }

    /**
     * @api 更新连衣裙
     * @param id 商品 ID
     * @param name 商品名称
     * @param size 尺码
     * @param length 长度
     * @param silhouette 廓形
     * @param neckline 领口
     * @param sleeveType 袖型
     * @param fabric 面料
     * @param color 颜色
     * @param price 价格
     * @param warehouseVolume 库存数量
     * @param status 商品状态
     * @param images 商品图片
     * @param highlight 商品卖点
     * @param description 商品描述
     * @param tagIds 标签 ID
     */
    @PutMapping("/api/admin/dresses/{id}")
    fun updateDress(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("size") size: Dress.Size,
        @RequestParam("length", required = false) length: Dress.Length?,
        @RequestParam("silhouette", required = false) silhouette: Dress.Silhouette?,
        @RequestParam("neckline", required = false) neckline: Dress.Neckline?,
        @RequestParam("sleeve_type", required = false) sleeveType: Dress.SleeveType?,
        @RequestParam("fabric", required = false) @Size(max = 100) fabric: String?,
        @RequestParam("color") @NotBlank @Size(max = 50) color: String,
        @RequestParam("price") @DecimalMin("0.01") price: BigDecimal,
        @RequestParam("warehouse_volume") @Min(0) warehouseVolume: Int,
        @RequestParam("status") status: Product.Status,
        @RequestParam("images", required = false) @Size(max = 12) images: List<String>?,
        @RequestParam("highlight", required = false) @Size(max = 10) highlight: List<String>?,
        @RequestParam("description", required = false) @Size(max = 4000) description: String?,
        @RequestParam("tag_ids", required = false) @Size(max = 20) tagIds: Set<Long>?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, val status: String)

        adminAccessService.requireAdmin(adminId)
        val source = Dress(size, length, silhouette, neckline, sleeveType, fabric).apply {
            this.name = name
            this.color = color
            this.price = price
            this.warehouseVolume = warehouseVolume
            this.status = status
            this.images = images.orEmpty().toMutableList()
            this.highlight = highlight.orEmpty().toMutableList()
            this.description = description
        }
        val dress = dressService.update(id, source, tagIds.orEmpty()) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(dress.id), dress.name, dress.status.name)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除连衣裙
     * @param id 商品 ID
     */
    @DeleteMapping("/api/admin/dresses/{id}")
    fun deleteDress(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!dressService.delete(id)) return builder.notFound().build()
        val rs = Response(id, true)
        return builder.ok().data(rs).build()
    }
}
