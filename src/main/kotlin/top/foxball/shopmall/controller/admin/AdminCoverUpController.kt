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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.CoverUpService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 商品/罩衫
 */
@Validated
@RestController
@RequestMapping("/admin/api/cover-ups")
class AdminCoverUpController(
    private val coverUpService: CoverUpService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {

    /**
     * @api 获取管理端罩衫列表
     */
    @GetMapping
    fun getAdminCoverUps(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class CoverUpData(
            val id: Long,
            val name: String,
            val style: String?,
            val size: String,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            val status: String,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<CoverUpData>)

        adminAccessService.requireAdmin(adminId)
        val list = coverUpService.listForAdmin().map {
            CoverUpData(
                id = requireNotNull(it.id),
                name = it.name,
                style = it.style?.name,
                size = it.size.name,
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
     * @api 获取管理端罩衫
     * @param id 商品 ID
     */
    @GetMapping("/{id}")
    fun getAdminCoverUp(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val style: String?,
            @param:JsonProperty("sheer_level")
            val sheerLevel: String?,
            val fabric: String?,
            val size: String,
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
        val coverUp = coverUpService.getForAdmin(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(coverUp.id),
            name = coverUp.name,
            style = coverUp.style?.name,
            sheerLevel = coverUp.sheerLevel?.name,
            fabric = coverUp.fabric,
            size = coverUp.size.name,
            color = coverUp.color,
            price = coverUp.price,
            warehouseVolume = coverUp.warehouseVolume,
            salesVolume = coverUp.salesVolume,
            status = coverUp.status.name,
            highlight = coverUp.highlight.toList(),
            images = coverUp.images.toList(),
            fitSense = coverUp.fitSense,
            description = coverUp.description,
            designAndExtras = coverUp.designAndExtras.toList(),
            careInstructions = coverUp.careInstructions.toList(),
            tagIds = coverUp.tags.mapNotNull { it.id },
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 创建罩衫
     * @param name 商品名称
     * @param style 罩衫款式
     * @param sheerLevel 透视程度
     * @param fabric 面料说明
     * @param size 尺码
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
    fun createCoverUp(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("style", required = false) style: CoverUp.CoverUpStyle?,
        @RequestParam("sheer_level", required = false) sheerLevel: CoverUp.SheerLevel?,
        @RequestParam("fabric", required = false) @Size(max = 100) fabric: String?,
        @RequestParam("size", defaultValue = "ONE_SIZE") size: CoverUp.Size,
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
        data class Response(val id: Long, val name: String, val status: String)

        adminAccessService.requireAdmin(adminId)
        val source = CoverUp(style, sheerLevel, fabric, size).apply {
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
        val coverUp = coverUpService.create(source, tagIds.orEmpty())
        val rs = Response(requireNotNull(coverUp.id), coverUp.name, coverUp.status.name)
        return builder.status(HttpStatus.CREATED).data(rs).build()
    }

    /**
     * @api 更新罩衫
     * @param id 商品 ID
     * @param name 商品名称
     * @param style 罩衫款式
     * @param sheerLevel 透视程度
     * @param fabric 面料说明
     * @param size 尺码
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
    fun updateCoverUp(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("style", required = false) style: CoverUp.CoverUpStyle?,
        @RequestParam("sheer_level", required = false) sheerLevel: CoverUp.SheerLevel?,
        @RequestParam("fabric", required = false) @Size(max = 100) fabric: String?,
        @RequestParam("size") size: CoverUp.Size,
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
        val source = CoverUp(style, sheerLevel, fabric, size).apply {
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
        val coverUp = coverUpService.update(id, source, tagIds.orEmpty()) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(coverUp.id), coverUp.name, coverUp.status.name, coverUp.updatedAt)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除罩衫
     * @param id 商品 ID
     */
    @DeleteMapping("/{id}")
    fun deleteCoverUp(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!coverUpService.delete(id)) return builder.notFound().build()
        val rs = Response(id, true)
        return builder.ok().data(rs).build()
    }
}
