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
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Product
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
class CoverUpController(
    private val coverUpService: CoverUpService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取已上架罩衫列表
     */
    @GetMapping("/api/cover-ups")
    fun getPublishedCoverUps(): ResponseEntity<Response> {
        data class CoverUpData(
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
            val description: String?,
            val score: Float?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
        )

        data class Response(val list: List<CoverUpData>)

        val list = coverUpService.listPublished().map {
            CoverUpData(
                id = requireNotNull(it.id),
                name = it.name,
                style = it.style?.name,
                sheerLevel = it.sheerLevel?.name,
                fabric = it.fabric,
                size = it.size.name,
                color = it.color,
                price = it.price,
                warehouseVolume = it.warehouseVolume,
                salesVolume = it.salesVolume,
                status = it.status.name,
                highlight = it.highlight.toList(),
                images = it.images.toList(),
                description = it.description,
                score = it.score,
                createdAt = it.createdAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取已上架罩衫
     * @param id 商品 ID
     */
    @GetMapping("/api/cover-ups/{id}")
    fun getPublishedCoverUp(
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
        )

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
            val score: Float?,
            val tags: List<TagData>,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val coverUp = coverUpService.getPublished(id) ?: return builder.notFound().build()
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
            score = coverUp.score,
            tags = coverUp.tags.sortedBy { it.sortOrder }.map {
                TagData(requireNotNull(it.id), it.name, it.color, it.sortOrder)
            },
            createdAt = coverUp.createdAt,
            updatedAt = coverUp.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

}
