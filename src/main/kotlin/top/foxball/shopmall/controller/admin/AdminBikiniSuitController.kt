package top.foxball.shopmall.controller.admin

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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.BikiniSuitService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

@Validated
@RestController
@RequestMapping("/admin/api/bikini-suits")
class AdminBikiniSuitController(
    private val bikiniSuitService: BikiniSuitService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取管理端比基尼列表
     */
    @GetMapping
    fun getAdminBikiniSuits(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class BikiniSuitData(
            val id: Long,
            val name: String,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            @param:JsonProperty("sales_volume")
            val salesVolume: Int,
            val status: String,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<BikiniSuitData>)

        adminAccessService.requireAdmin(adminId)
        val list = bikiniSuitService.listForAdmin().map {
            BikiniSuitData(
                id = requireNotNull(it.id),
                name = it.name,
                topSize = it.topSize?.name,
                bottomSize = it.bottomSize?.name,
                color = it.color,
                price = it.price,
                warehouseVolume = it.warehouseVolume,
                salesVolume = it.salesVolume,
                status = it.status.name,
                updatedAt = it.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端比基尼
     * @param id 商品 ID
     */
    @GetMapping("/{id}")
    fun getAdminBikiniSuit(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
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
            @param:JsonProperty("tag_ids")
            val tagIds: List<Long>,
        )

        adminAccessService.requireAdmin(adminId)
        val bikiniSuit = bikiniSuitService.getForAdmin(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(bikiniSuit.id),
            name = bikiniSuit.name,
            topSize = bikiniSuit.topSize?.name,
            bottomSize = bikiniSuit.bottomSize?.name,
            color = bikiniSuit.color,
            price = bikiniSuit.price,
            warehouseVolume = bikiniSuit.warehouseVolume,
            salesVolume = bikiniSuit.salesVolume,
            status = bikiniSuit.status.name,
            highlight = bikiniSuit.highlight.toList(),
            images = bikiniSuit.images.toList(),
            fitSense = bikiniSuit.fitSense,
            description = bikiniSuit.description,
            designAndExtras = bikiniSuit.designAndExtras.toList(),
            careInstructions = bikiniSuit.careInstructions.toList(),
            tagIds = bikiniSuit.tags.mapNotNull { it.id },
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 创建比基尼
     * @param name 商品名称
     * @param topSize 上装尺码
     * @param bottomSize 下装尺码
     * @param color 颜色
     * @param price 价格
     * @param warehouseVolume 库存数量
     * @param salesVolume 销售数量
     * @param status 商品状态
     * @param highlight 商品卖点
     * @param images 商品图片
     * @param fitSense 穿着感受
     * @param description 商品描述
     * @param designAndExtras 设计细节
     * @param careInstructions 洗护说明
     * @param tagIds 标签 ID
     */
    @PostMapping
    fun createBikiniSuit(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("top_size", required = false) topSize: BikiniSuit.Size?,
        @RequestParam("bottom_size", required = false) bottomSize: BikiniSuit.Size?,
        @RequestParam("color") @NotBlank @Size(max = 50) color: String,
        @RequestParam("price") @DecimalMin("0.01") price: BigDecimal,
        @RequestParam("warehouse_volume", defaultValue = "0") @Min(0) warehouseVolume: Int,
        @RequestParam("sales_volume", defaultValue = "0") @Min(0) salesVolume: Int,
        @RequestParam("status", defaultValue = "ACTIVE") status: Product.Status,
        @RequestParam("highlight", required = false) @Size(max = 10) highlight: List<String>?,
        @RequestParam("images", required = false) @Size(max = 12) images: List<String>?,
        @RequestParam("fit_sense", required = false) @Size(max = 255) fitSense: String?,
        @RequestParam("description", required = false) @Size(max = 4000) description: String?,
        @RequestParam("design_and_extras", required = false) @Size(max = 12) designAndExtras: List<String>?,
        @RequestParam("care_instructions", required = false) @Size(max = 12) careInstructions: List<String>?,
        @RequestParam("tag_ids", required = false) @Size(max = 20) tagIds: Set<Long>?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val status: String,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val source = BikiniSuit(topSize, bottomSize).apply {
            this.name = name
            this.color = color
            this.price = price
            this.warehouseVolume = warehouseVolume
            this.salesVolume = salesVolume
            this.status = status
            this.highlight = highlight.orEmpty().toMutableList()
            this.images = images.orEmpty().toMutableList()
            this.fitSense = fitSense
            this.description = description
            this.designAndExtras = designAndExtras.orEmpty().toMutableList()
            this.careInstructions = careInstructions.orEmpty().toMutableList()
        }
        val bikiniSuit = bikiniSuitService.create(source, tagIds.orEmpty())
        val rs = Response(
            id = requireNotNull(bikiniSuit.id),
            name = bikiniSuit.name,
            status = bikiniSuit.status.name,
            createdAt = bikiniSuit.createdAt,
        )
        return builder.status(HttpStatus.CREATED).data(rs).build()
    }

    /**
     * @api 更新比基尼
     * @param id 商品 ID
     * @param name 商品名称
     * @param topSize 上装尺码
     * @param bottomSize 下装尺码
     * @param color 颜色
     * @param price 价格
     * @param warehouseVolume 库存数量
     * @param salesVolume 销售数量
     * @param status 商品状态
     * @param highlight 商品卖点
     * @param images 商品图片
     * @param fitSense 穿着感受
     * @param description 商品描述
     * @param designAndExtras 设计细节
     * @param careInstructions 洗护说明
     * @param tagIds 标签 ID
     */
    @PutMapping("/{id}")
    fun updateBikiniSuit(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("top_size", required = false) topSize: BikiniSuit.Size?,
        @RequestParam("bottom_size", required = false) bottomSize: BikiniSuit.Size?,
        @RequestParam("color") @NotBlank @Size(max = 50) color: String,
        @RequestParam("price") @DecimalMin("0.01") price: BigDecimal,
        @RequestParam("warehouse_volume") @Min(0) warehouseVolume: Int,
        @RequestParam("sales_volume") @Min(0) salesVolume: Int,
        @RequestParam("status") status: Product.Status,
        @RequestParam("highlight", required = false) @Size(max = 10) highlight: List<String>?,
        @RequestParam("images", required = false) @Size(max = 12) images: List<String>?,
        @RequestParam("fit_sense", required = false) @Size(max = 255) fitSense: String?,
        @RequestParam("description", required = false) @Size(max = 4000) description: String?,
        @RequestParam("design_and_extras", required = false) @Size(max = 12) designAndExtras: List<String>?,
        @RequestParam("care_instructions", required = false) @Size(max = 12) careInstructions: List<String>?,
        @RequestParam("tag_ids", required = false) @Size(max = 20) tagIds: Set<Long>?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val status: String,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val source = BikiniSuit(topSize, bottomSize).apply {
            this.name = name
            this.color = color
            this.price = price
            this.warehouseVolume = warehouseVolume
            this.salesVolume = salesVolume
            this.status = status
            this.highlight = highlight.orEmpty().toMutableList()
            this.images = images.orEmpty().toMutableList()
            this.fitSense = fitSense
            this.description = description
            this.designAndExtras = designAndExtras.orEmpty().toMutableList()
            this.careInstructions = careInstructions.orEmpty().toMutableList()
        }
        val bikiniSuit = bikiniSuitService.update(id, source, tagIds.orEmpty())
            ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(bikiniSuit.id),
            name = bikiniSuit.name,
            status = bikiniSuit.status.name,
            updatedAt = bikiniSuit.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除比基尼
     * @param id 商品 ID
     */
    @DeleteMapping("/{id}")
    fun deleteBikiniSuit(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val deleted: Boolean,
        )

        adminAccessService.requireAdmin(adminId)
        if (!bikiniSuitService.delete(id)) return builder.notFound().build()
        val rs = Response(id, true)
        return builder.ok().data(rs).build()
    }
}
