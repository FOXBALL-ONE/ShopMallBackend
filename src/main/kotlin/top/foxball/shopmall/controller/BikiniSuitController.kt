package top.foxball.shopmall.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.BikiniSuitSizeRecommendation
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.BikiniSuitService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime
import top.foxball.shopmall.shared.Response as ApiResponse

/** 商品写入请求；标签 ID 独立传递，避免客户端直接构造持久化标签实体。 */
data class BikiniSuitUpsertRequest(
    @field:Valid
    val bikiniSuit: BikiniSuit = BikiniSuit(),
    @field:Size(max = 20)
    val tagIds: Set<Long> = emptySet(),
)

private data class BikiniSuitResponse(
    val id: Long,
    val name: String,
    val topSize: BikiniSuit.Size?,
    val topSizeRecommendation: BikiniSuitSizeRecommendation?,
    val bottomSize: BikiniSuit.Size?,
    val bottomSizeRecommendation: BikiniSuitSizeRecommendation?,
    val color: String,
    val price: BigDecimal,
    val warehouseVolume: Int,
    val salesVolume: Int,
    val status: BikiniSuit.Status,
    val highlight: List<String>,
    val images: List<String>,
    val fitSense: String?,
    val description: String?,
    val designAndExtras: List<String>,
    val careInstructions: List<String>,
    val score: Float?,
    val tags: List<Tag>,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
)

private fun BikiniSuit.toResponse(): BikiniSuitResponse = BikiniSuitResponse(
    id = requireNotNull(id),
    name = name,
    topSize = topSize,
    topSizeRecommendation = topSize?.recommendation,
    bottomSize = bottomSize,
    bottomSizeRecommendation = bottomSize?.recommendation,
    color = color,
    price = price,
    warehouseVolume = warehouseVolume,
    salesVolume = salesVolume,
    status = status,
    highlight = highlight.toList(),
    images = images.toList(),
    fitSense = fitSense,
    description = description,
    designAndExtras = designAndExtras.toList(),
    careInstructions = careInstructions.toList(),
    score = score,
    tags = tags.sortedBy(Tag::sortOrder),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** 面向前台的上架商品读取接口，以及管理员商品目录 CRUD 接口。 */
@RestController
@RequestMapping
class BikiniSuitController(
    private val bikiniSuitService: BikiniSuitService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/bikini-suits")
    fun listPublished(): ResponseEntity<ApiResponse> {
        data class Response(val bikiniSuits: List<BikiniSuitResponse>)
        return builder.ok().data(Response(bikiniSuitService.listPublished().map(BikiniSuit::toResponse))).build()
    }

    @GetMapping("/api/bikini-suits/{id}")
    fun getPublished(@PathVariable id: Long): ResponseEntity<ApiResponse> {
        data class Response(val bikiniSuit: BikiniSuitResponse)
        val bikiniSuit = bikiniSuitService.getPublished(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(bikiniSuit.toResponse())).build()
    }

    @GetMapping("/api/admin/bikini-suits")
    fun listForAdmin(@AuthenticationPrincipal userId: Long): ResponseEntity<ApiResponse> {
        data class Response(val bikiniSuits: List<BikiniSuitResponse>)
        adminAccessService.requireAdmin(userId)
        return builder.ok().data(Response(bikiniSuitService.listForAdmin().map(BikiniSuit::toResponse))).build()
    }

    @GetMapping("/api/admin/bikini-suits/{id}")
    fun getForAdmin(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val bikiniSuit: BikiniSuitResponse)
        adminAccessService.requireAdmin(userId)
        val bikiniSuit = bikiniSuitService.getForAdmin(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(bikiniSuit.toResponse())).build()
    }

    @PostMapping("/api/admin/bikini-suits")
    fun create(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: BikiniSuitUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val bikiniSuit: BikiniSuitResponse)
        adminAccessService.requireAdmin(userId)
        val bikiniSuit = bikiniSuitService.create(request.bikiniSuit, request.tagIds)
        return builder.status(HttpStatus.CREATED).data(Response(bikiniSuit.toResponse())).build()
    }

    @PutMapping("/api/admin/bikini-suits/{id}")
    fun update(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: BikiniSuitUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val bikiniSuit: BikiniSuitResponse)
        adminAccessService.requireAdmin(userId)
        val bikiniSuit = bikiniSuitService.update(id, request.bikiniSuit, request.tagIds)
            ?: return builder.notFound().build()
        return builder.ok().data(Response(bikiniSuit.toResponse())).build()
    }

    @DeleteMapping("/api/admin/bikini-suits/{id}")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val id: Long, val deleted: Boolean)
        adminAccessService.requireAdmin(userId)
        if (!bikiniSuitService.delete(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }
}
