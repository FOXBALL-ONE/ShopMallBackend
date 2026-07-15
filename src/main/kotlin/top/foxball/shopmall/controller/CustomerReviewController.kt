package top.foxball.shopmall.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.ReviewStatus
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.CustomerReviewService
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime
import top.foxball.shopmall.shared.Response as ApiResponse

/** 客户可编辑的评价内容；商品和客户归属均由 URL 与登录态决定。 */
data class CustomerReviewContentRequest(
    @field:Min(1)
    @field:Max(5)
    val rating: Int = 0,
    @field:Size(max = 100)
    val title: String? = null,
    @field:NotBlank
    @field:Size(max = 2_000)
    val content: String = "",
) {
    fun toEntity(): CustomerReview = CustomerReview(rating = rating, title = title, content = content)
}

/** 管理员审核评价及维护商家回复的请求。 */
data class CustomerReviewModerationRequest(
    @field:NotNull
    val status: ReviewStatus? = null,
    val verifiedPurchase: Boolean? = null,
    @field:Size(max = 1_000)
    val merchantReply: String? = null,
)

private data class CustomerReviewResponse(
    val id: Long,
    val bikiniSuitId: Long,
    val rating: Int,
    val title: String?,
    val content: String,
    val verifiedPurchase: Boolean,
    val status: ReviewStatus,
    val merchantReply: String?,
    val merchantRepliedAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val customerId: Long? = null,
)

private fun CustomerReview.toResponse(includeCustomerId: Boolean = false): CustomerReviewResponse = CustomerReviewResponse(
    id = requireNotNull(id),
    bikiniSuitId = requireNotNull(bikiniSuit?.id),
    rating = rating,
    title = title,
    content = content,
    verifiedPurchase = verifiedPurchase,
    status = status,
    merchantReply = merchantReply,
    merchantRepliedAt = merchantRepliedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    customerId = customerId.takeIf { includeCustomerId },
)

/** 前台已审核评价读取、客户自有评价 CRUD 与管理员审核接口。 */
@RestController
@RequestMapping
class CustomerReviewController(
    private val customerReviewService: CustomerReviewService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/bikini-suits/{bikiniSuitId}/reviews")
    fun listPublished(@PathVariable bikiniSuitId: Long): ResponseEntity<ApiResponse> {
        data class Response(val reviews: List<CustomerReviewResponse>)
        val reviews = customerReviewService.listPublishedByBikiniSuit(bikiniSuitId).map(CustomerReview::toResponse)
        return builder.ok().data(Response(reviews)).build()
    }

    @GetMapping("/api/customer-reviews/{id}")
    fun getPublished(@PathVariable id: Long): ResponseEntity<ApiResponse> {
        data class Response(val review: CustomerReviewResponse)
        val review = customerReviewService.getPublished(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(review.toResponse())).build()
    }

    @PostMapping("/api/bikini-suits/{bikiniSuitId}/reviews")
    fun create(
        @AuthenticationPrincipal userId: Long,
        @PathVariable bikiniSuitId: Long,
        @Valid @RequestBody request: CustomerReviewContentRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val review: CustomerReviewResponse)
        adminAccessService.requireCustomer(userId)
        val review = customerReviewService.create(userId, bikiniSuitId, request.toEntity())
            ?: return builder.notFound().build()
        return builder.status(HttpStatus.CREATED).data(Response(review.toResponse())).build()
    }

    @PutMapping("/api/customer-reviews/{id}")
    fun updateByCustomer(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: CustomerReviewContentRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val review: CustomerReviewResponse)
        adminAccessService.requireCustomer(userId)
        val review = customerReviewService.updateByCustomer(id, userId, request.toEntity())
            ?: return builder.notFound().build()
        return builder.ok().data(Response(review.toResponse())).build()
    }

    @DeleteMapping("/api/customer-reviews/{id}")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val id: Long, val deleted: Boolean)
        val deleted = customerReviewService.delete(id, userId, adminAccessService.isAdmin(userId))
            ?: return builder.notFound().build()
        return builder.ok().data(Response(id, deleted)).build()
    }

    @GetMapping("/api/admin/customer-reviews")
    fun listForAdmin(@AuthenticationPrincipal userId: Long): ResponseEntity<ApiResponse> {
        data class Response(val reviews: List<CustomerReviewResponse>)
        adminAccessService.requireAdmin(userId)
        val reviews = customerReviewService.listForAdmin().map { it.toResponse(includeCustomerId = true) }
        return builder.ok().data(Response(reviews)).build()
    }

    @GetMapping("/api/admin/customer-reviews/{id}")
    fun getForAdmin(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val review: CustomerReviewResponse)
        adminAccessService.requireAdmin(userId)
        val review = customerReviewService.getForAdmin(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(review.toResponse(includeCustomerId = true))).build()
    }

    @PutMapping("/api/admin/customer-reviews/{id}/moderation")
    fun moderate(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: CustomerReviewModerationRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val review: CustomerReviewResponse)
        adminAccessService.requireAdmin(userId)
        val review = customerReviewService.moderate(
            id,
            requireNotNull(request.status),
            request.verifiedPurchase,
            request.merchantReply,
        ) ?: return builder.notFound().build()
        return builder.ok().data(Response(review.toResponse(includeCustomerId = true))).build()
    }
}
