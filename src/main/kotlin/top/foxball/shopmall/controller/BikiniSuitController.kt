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
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.SizeRecommendation
import top.foxball.shopmall.service.BikiniSuitService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 商品/比基尼
 */
@Validated
@RestController
class BikiniSuitController(
    private val bikiniSuitService: BikiniSuitService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取已上架比基尼列表
     */
    @GetMapping("/api/bikini-suits")
    fun getPublishedBikiniSuits(): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
        )

        data class BikiniSuitData(
            val id: Long,
            val name: String,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("top_size_recommendation")
            val topSizeRecommendation: SizeRecommendation?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("bottom_size_recommendation")
            val bottomSizeRecommendation: SizeRecommendation?,
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

        data class Response(val list: List<BikiniSuitData>)

        val list = bikiniSuitService.listPublished().map { bikiniSuit ->
            BikiniSuitData(
                id = requireNotNull(bikiniSuit.id),
                name = bikiniSuit.name,
                topSize = bikiniSuit.topSize?.name,
                topSizeRecommendation = bikiniSuit.topSize?.recommendation,
                bottomSize = bikiniSuit.bottomSize?.name,
                bottomSizeRecommendation = bikiniSuit.bottomSize?.recommendation,
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
                score = bikiniSuit.score,
                tags = bikiniSuit.tags.sortedBy { it.sortOrder }.map {
                    TagData(requireNotNull(it.id), it.name, it.description, it.color, it.sortOrder)
                },
                createdAt = bikiniSuit.createdAt,
                updatedAt = bikiniSuit.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取已上架比基尼
     * @param id 商品 ID
     */
    @GetMapping("/api/bikini-suits/{id}")
    fun getPublishedBikiniSuit(
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
        )

        data class Response(
            val id: Long,
            val name: String,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("top_size_recommendation")
            val topSizeRecommendation: SizeRecommendation?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("bottom_size_recommendation")
            val bottomSizeRecommendation: SizeRecommendation?,
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

        val bikiniSuit = bikiniSuitService.getPublished(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(bikiniSuit.id),
            name = bikiniSuit.name,
            topSize = bikiniSuit.topSize?.name,
            topSizeRecommendation = bikiniSuit.topSize?.recommendation,
            bottomSize = bikiniSuit.bottomSize?.name,
            bottomSizeRecommendation = bikiniSuit.bottomSize?.recommendation,
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
            score = bikiniSuit.score,
            tags = bikiniSuit.tags.sortedBy { it.sortOrder }.map {
                TagData(requireNotNull(it.id), it.name, it.description, it.color, it.sortOrder)
            },
            createdAt = bikiniSuit.createdAt,
            updatedAt = bikiniSuit.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

}
