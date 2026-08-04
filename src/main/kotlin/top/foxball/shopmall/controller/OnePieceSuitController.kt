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
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.SizeRecommendation
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
class OnePieceSuitController(
    private val onePieceSuitService: OnePieceSuitService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取已上架一件式泳衣列表
     */
    @GetMapping("/api/one-piece-suits")
    fun getPublishedOnePieceSuits(): ResponseEntity<Response> {
        data class SuitData(
            val id: Long,
            val name: String,
            val size: String,
            @param:JsonProperty("size_recommendation")
            val sizeRecommendation: SizeRecommendation?,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            @param:JsonProperty("sales_volume")
            val salesVolume: Int,
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
            val status: String,
            val images: List<String>,
            val highlight: List<String>,
            val description: String?,
            val score: Float?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
        )

        data class Response(val list: List<SuitData>)

        val list = onePieceSuitService.listPublished().map { suit ->
            val size = requireNotNull(suit.size)
            SuitData(
                id = requireNotNull(suit.id),
                name = suit.name,
                size = size.name,
                sizeRecommendation = size.recommendation,
                color = suit.color,
                price = suit.price,
                warehouseVolume = suit.warehouseVolume,
                salesVolume = suit.salesVolume,
                supportLevel = suit.supportLevel?.name,
                coverage = suit.coverage?.name,
                torsoFit = suit.torsoFit?.name,
                neckline = suit.neckline?.name,
                backStyle = suit.backStyle?.name,
                tummyControl = suit.tummyControl,
                removablePadding = suit.removablePadding,
                status = suit.status.name,
                images = suit.images.toList(),
                highlight = suit.highlight.toList(),
                description = suit.description,
                score = suit.score,
                createdAt = suit.createdAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取已上架一件式泳衣
     * @param id 商品 ID
     */
    @GetMapping("/api/one-piece-suits/{id}")
    fun getPublishedOnePieceSuit(
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
            val size: String,
            @param:JsonProperty("size_recommendation")
            val sizeRecommendation: SizeRecommendation?,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            @param:JsonProperty("sales_volume")
            val salesVolume: Int,
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

        val suit = onePieceSuitService.getPublished(id) ?: return builder.notFound().build()
        val size = requireNotNull(suit.size)
        val rs = Response(
            id = requireNotNull(suit.id),
            name = suit.name,
            size = size.name,
            sizeRecommendation = size.recommendation,
            color = suit.color,
            price = suit.price,
            warehouseVolume = suit.warehouseVolume,
            salesVolume = suit.salesVolume,
            supportLevel = suit.supportLevel?.name,
            coverage = suit.coverage?.name,
            torsoFit = suit.torsoFit?.name,
            neckline = suit.neckline?.name,
            backStyle = suit.backStyle?.name,
            tummyControl = suit.tummyControl,
            removablePadding = suit.removablePadding,
            status = suit.status.name,
            highlight = suit.highlight.toList(),
            images = suit.images.toList(),
            fitSense = suit.fitSense,
            description = suit.description,
            designAndExtras = suit.designAndExtras.toList(),
            careInstructions = suit.careInstructions.toList(),
            score = suit.score,
            tags = suit.tags.sortedBy { it.sortOrder }.map {
                TagData(requireNotNull(it.id), it.name, it.color, it.sortOrder)
            },
            createdAt = suit.createdAt,
            updatedAt = suit.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

}
