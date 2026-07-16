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
import top.foxball.shopmall.entity.jdbc.BikiniSuitSizeRecommendation
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.OnePieceSuitService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime
import top.foxball.shopmall.shared.Response as ApiResponse

/** 商品写入请求；标签 ID 独立传递，避免客户端直接构造持久化标签实体。 */
data class OnePieceSuitUpsertRequest(
    @field:Valid
    val onePieceSuit: OnePieceSuit = OnePieceSuit(),
    @field:Size(max = 20)
    val tagIds: Set<Long> = emptySet(),
)

private data class OnePieceSuitResponse(
    val id: Long,
    val name: String,
    val size: OnePieceSuit.Size,
    val sizeRecommendation: BikiniSuitSizeRecommendation?,
    val color: String,
    val price: BigDecimal,
    val warehouseVolume: Int,
    val salesVolume: Int,
    val supportLevel: OnePieceSuit.SupportLevel?,
    val coverage: OnePieceSuit.Coverage?,
    val torsoFit: OnePieceSuit.TorsoFit?,
    val neckline: OnePieceSuit.Neckline?,
    val backStyle: OnePieceSuit.BackStyle?,
    val tummyControl: Boolean,
    val removablePadding: Boolean,
    val status: OnePieceSuit.Status,
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

private fun OnePieceSuit.toResponse(): OnePieceSuitResponse {
    val requiredSize = requireNotNull(size)
    return OnePieceSuitResponse(
        id = requireNotNull(id),
        name = name,
        size = requiredSize,
        sizeRecommendation = requiredSize.recommendation,
        color = color,
        price = price,
        warehouseVolume = warehouseVolume,
        salesVolume = salesVolume,
        supportLevel = supportLevel,
        coverage = coverage,
        torsoFit = torsoFit,
        neckline = neckline,
        backStyle = backStyle,
        tummyControl = tummyControl,
        removablePadding = removablePadding,
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
}

/** 消费者读取上架商品，商城管理员维护全部一件式泳衣目录。 */
@RestController
@RequestMapping
class OnePieceSuitController(
    private val onePieceSuitService: OnePieceSuitService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/one-piece-suits")
    fun listPublished(): ResponseEntity<ApiResponse> {
        data class Response(val onePieceSuits: List<OnePieceSuitResponse>)
        return builder.ok().data(Response(onePieceSuitService.listPublished().map(OnePieceSuit::toResponse))).build()
    }

    @GetMapping("/api/one-piece-suits/{id}")
    fun getPublished(@PathVariable id: Long): ResponseEntity<ApiResponse> {
        data class Response(val onePieceSuit: OnePieceSuitResponse)
        val onePieceSuit = onePieceSuitService.getPublished(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(onePieceSuit.toResponse())).build()
    }

    @GetMapping("/api/admin/one-piece-suits")
    fun listForAdmin(@AuthenticationPrincipal userId: Long): ResponseEntity<ApiResponse> {
        data class Response(val onePieceSuits: List<OnePieceSuitResponse>)
        adminAccessService.requireAdmin(userId)
        return builder.ok().data(Response(onePieceSuitService.listForAdmin().map(OnePieceSuit::toResponse))).build()
    }

    @GetMapping("/api/admin/one-piece-suits/{id}")
    fun getForAdmin(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val onePieceSuit: OnePieceSuitResponse)
        adminAccessService.requireAdmin(userId)
        val onePieceSuit = onePieceSuitService.getForAdmin(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(onePieceSuit.toResponse())).build()
    }

    @PostMapping("/api/admin/one-piece-suits")
    fun create(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: OnePieceSuitUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val onePieceSuit: OnePieceSuitResponse)
        adminAccessService.requireAdmin(userId)
        val onePieceSuit = onePieceSuitService.create(request.onePieceSuit, request.tagIds)
        return builder.status(HttpStatus.CREATED).data(Response(onePieceSuit.toResponse())).build()
    }

    @PutMapping("/api/admin/one-piece-suits/{id}")
    fun update(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: OnePieceSuitUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val onePieceSuit: OnePieceSuitResponse)
        adminAccessService.requireAdmin(userId)
        val onePieceSuit = onePieceSuitService.update(id, request.onePieceSuit, request.tagIds)
            ?: return builder.notFound().build()
        return builder.ok().data(Response(onePieceSuit.toResponse())).build()
    }

    @DeleteMapping("/api/admin/one-piece-suits/{id}")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val id: Long, val deleted: Boolean)
        adminAccessService.requireAdmin(userId)
        if (!onePieceSuitService.delete(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }
}
