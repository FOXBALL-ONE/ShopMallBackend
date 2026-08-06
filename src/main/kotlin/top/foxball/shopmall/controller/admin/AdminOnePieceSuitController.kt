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
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.SizeRecommendation
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.OnePieceSuitService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 商品/一件式泳衣
 */
@Validated
@RestController
@RequestMapping("/admin/api/one-piece-suits")
class AdminOnePieceSuitController(
    private val onePieceSuitService: OnePieceSuitService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {

    /**
     * @api 获取管理端一件式泳衣列表
     */
    @GetMapping
    fun getAdminOnePieceSuits(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class SuitData(
            val id: Long,
            val name: String,
            val size: String,
            @param:JsonProperty("support_level")
            val supportLevel: String?,
            val coverage: String?,
            @param:JsonProperty("torso_fit")
            val torsoFit: String?,
            val neckline: String?,
            @param:JsonProperty("back_style")
            val backStyle: String?,
            @param:JsonProperty("tummy_control")
            val tummyControl: Boolean,
            @param:JsonProperty("removable_padding")
            val removablePadding: Boolean,
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
            @param:JsonProperty("tag_ids")
            val tagIds: List<Long>,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<SuitData>)

        adminAccessService.requireAdmin(adminId)
        val list = onePieceSuitService.listForAdmin().map {
            SuitData(
                id = requireNotNull(it.id),
                name = it.name,
                size = requireNotNull(it.size).name,
                supportLevel = it.supportLevel?.name,
                coverage = it.coverage?.name,
                torsoFit = it.torsoFit?.name,
                neckline = it.neckline?.name,
                backStyle = it.backStyle?.name,
                tummyControl = it.tummyControl,
                removablePadding = it.removablePadding,
                color = it.color,
                price = it.price,
                warehouseVolume = it.warehouseVolume,
                salesVolume = it.salesVolume,
                status = it.status.name,
                highlight = it.highlight.toList(),
                images = it.images.toList(),
                fitSense = it.fitSense,
                description = it.description,
                designAndExtras = it.designAndExtras.toList(),
                careInstructions = it.careInstructions.toList(),
                score = it.score,
                tagIds = it.tags.mapNotNull { tag -> tag.id },
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端一件式泳衣
     * @param id 商品 ID
     */
    @GetMapping("/{id}")
    fun getAdminOnePieceSuit(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val size: String,
            @param:JsonProperty("support_level")
            val supportLevel: String?,
            val coverage: String?,
            @param:JsonProperty("torso_fit")
            val torsoFit: String?,
            val neckline: String?,
            @param:JsonProperty("back_style")
            val backStyle: String?,
            @param:JsonProperty("tummy_control")
            val tummyControl: Boolean,
            @param:JsonProperty("removable_padding")
            val removablePadding: Boolean,
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
            @param:JsonProperty("tag_ids")
            val tagIds: List<Long>,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val suit = onePieceSuitService.getForAdmin(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(suit.id),
            name = suit.name,
            size = requireNotNull(suit.size).name,
            supportLevel = suit.supportLevel?.name,
            coverage = suit.coverage?.name,
            torsoFit = suit.torsoFit?.name,
            neckline = suit.neckline?.name,
            backStyle = suit.backStyle?.name,
            tummyControl = suit.tummyControl,
            removablePadding = suit.removablePadding,
            color = suit.color,
            price = suit.price,
            warehouseVolume = suit.warehouseVolume,
            salesVolume = suit.salesVolume,
            status = suit.status.name,
            highlight = suit.highlight.toList(),
            images = suit.images.toList(),
            fitSense = suit.fitSense,
            description = suit.description,
            designAndExtras = suit.designAndExtras.toList(),
            careInstructions = suit.careInstructions.toList(),
            score = suit.score,
            tagIds = suit.tags.mapNotNull { it.id },
            createdAt = suit.createdAt,
            updatedAt = suit.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 创建一件式泳衣
     * @param name 商品名称
     * @param size 尺码
     * @param color 颜色
     * @param price 价格
     * @param warehouseVolume 库存数量
     * @param supportLevel 支撑程度
     * @param coverage 覆盖程度
     * @param torsoFit 躯干版型
     * @param neckline 领口设计
     * @param backStyle 后背设计
     * @param tummyControl 是否腹部塑形
     * @param removablePadding 是否可拆卸胸垫
     * @param status 商品状态
     * @param highlight 商品卖点
     * @param images 商品图片
     * @param description 商品描述
     * @param tagIds 标签 ID
     */
    @PostMapping
    fun createOnePieceSuit(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("size") size: OnePieceSuit.Size,
        @RequestParam("color") @NotBlank @Size(max = 50) color: String,
        @RequestParam("price") @DecimalMin("0.01") price: BigDecimal,
        @RequestParam("warehouse_volume", defaultValue = "0") @Min(0) warehouseVolume: Int,
        @RequestParam("support_level", required = false) supportLevel: OnePieceSuit.SupportLevel?,
        @RequestParam("coverage", required = false) coverage: OnePieceSuit.Coverage?,
        @RequestParam("torso_fit", required = false) torsoFit: OnePieceSuit.TorsoFit?,
        @RequestParam("neckline", required = false) neckline: OnePieceSuit.Neckline?,
        @RequestParam("back_style", required = false) backStyle: OnePieceSuit.BackStyle?,
        @RequestParam("tummy_control", defaultValue = "false") tummyControl: Boolean,
        @RequestParam("removable_padding", defaultValue = "false") removablePadding: Boolean,
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
        val source = OnePieceSuit(
            size = size,
            supportLevel = supportLevel,
            coverage = coverage,
            torsoFit = torsoFit,
            neckline = neckline,
            backStyle = backStyle,
            tummyControl = tummyControl,
            removablePadding = removablePadding,
        ).apply {
            this.name = name
            this.color = color
            this.price = price
            this.warehouseVolume = warehouseVolume
            this.status = status
            this.highlight = highlight.orEmpty().toMutableList()
            this.images = images.orEmpty().toMutableList()
            this.fitSense = fitSense
            this.description = description
            this.designAndExtras = designAndExtras.orEmpty().toMutableList()
            this.careInstructions = careInstructions.orEmpty().toMutableList()
        }
        val suit = onePieceSuitService.create(source, tagIds.orEmpty())
        val rs = Response(requireNotNull(suit.id), suit.name, suit.status.name, suit.createdAt)
        return builder.status(HttpStatus.CREATED).data(rs).build()
    }

    /**
     * @api 更新一件式泳衣
     * @param id 商品 ID
     * @param name 商品名称
     * @param size 尺码
     * @param color 颜色
     * @param price 价格
     * @param supportLevel 支撑程度
     * @param coverage 覆盖程度
     * @param torsoFit 躯干版型
     * @param neckline 领口设计
     * @param backStyle 后背设计
     * @param tummyControl 是否腹部塑形
     * @param removablePadding 是否可拆卸胸垫
     * @param highlight 商品卖点
     * @param images 商品图片
     * @param description 商品描述
     * @param tagIds 标签 ID
     */
    @PutMapping("/{id}")
    fun updateOnePieceSuit(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("name") @NotBlank @Size(max = 200) name: String,
        @RequestParam("size") size: OnePieceSuit.Size,
        @RequestParam("color") @NotBlank @Size(max = 50) color: String,
        @RequestParam("price") @DecimalMin("0.01") price: BigDecimal,
        @RequestParam("support_level", required = false) supportLevel: OnePieceSuit.SupportLevel?,
        @RequestParam("coverage", required = false) coverage: OnePieceSuit.Coverage?,
        @RequestParam("torso_fit", required = false) torsoFit: OnePieceSuit.TorsoFit?,
        @RequestParam("neckline", required = false) neckline: OnePieceSuit.Neckline?,
        @RequestParam("back_style", required = false) backStyle: OnePieceSuit.BackStyle?,
        @RequestParam("tummy_control") tummyControl: Boolean,
        @RequestParam("removable_padding") removablePadding: Boolean,
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
        val source = OnePieceSuit(
            size = size,
            supportLevel = supportLevel,
            coverage = coverage,
            torsoFit = torsoFit,
            neckline = neckline,
            backStyle = backStyle,
            tummyControl = tummyControl,
            removablePadding = removablePadding,
        ).apply {
            this.name = name
            this.color = color
            this.price = price
            this.highlight = highlight.orEmpty().toMutableList()
            this.images = images.orEmpty().toMutableList()
            this.fitSense = fitSense
            this.description = description
            this.designAndExtras = designAndExtras.orEmpty().toMutableList()
            this.careInstructions = careInstructions.orEmpty().toMutableList()
        }
        val suit = onePieceSuitService.update(id, source, tagIds.orEmpty())
            ?: return builder.notFound().build()
        val rs = Response(requireNotNull(suit.id), suit.name, suit.status.name, suit.updatedAt)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除一件式泳衣
     * @param id 商品 ID
     */
    @DeleteMapping("/{id}")
    fun deleteOnePieceSuit(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!onePieceSuitService.delete(id)) return builder.notFound().build()
        val rs = Response(id, true)
        return builder.ok().data(rs).build()
    }
}
