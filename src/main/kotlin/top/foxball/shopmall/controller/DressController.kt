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

}
