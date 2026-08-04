package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
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
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.ReviewStatus
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.CustomerReviewService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/**
 * @folder 商品/评价
 */
@Validated
@RestController
@RequestMapping("/admin/api/customer-reviews")
class AdminCustomerReviewController(
    private val customerReviewService: CustomerReviewService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {

    /**
     * @api 获取管理端评价列表
     */
    @GetMapping
    fun getAdminReviews(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class ReviewData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val rating: Int,
            val title: String?,
            val content: String,
            @param:JsonProperty("verified_purchase")
            val verifiedPurchase: Boolean,
            val status: String,
            @param:JsonProperty("merchant_reply")
            val merchantReply: String?,
            @param:JsonProperty("merchant_replied_at")
            val merchantRepliedAt: LocalDateTime?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<ReviewData>)

        adminAccessService.requireAdmin(adminId)
        val list = customerReviewService.listForAdmin().map {
            ReviewData(
                id = requireNotNull(it.id),
                productId = requireNotNull(it.product?.id),
                customerId = it.customerId,
                rating = it.rating,
                title = it.title,
                content = it.content,
                verifiedPurchase = it.verifiedPurchase,
                status = it.status.name,
                merchantReply = it.merchantReply,
                merchantRepliedAt = it.merchantRepliedAt,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端评价
     * @param id 评价 ID
     */
    @GetMapping("/{id}")
    fun getAdminReview(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val rating: Int,
            val title: String?,
            val content: String,
            @param:JsonProperty("verified_purchase")
            val verifiedPurchase: Boolean,
            val status: String,
            @param:JsonProperty("merchant_reply")
            val merchantReply: String?,
            @param:JsonProperty("merchant_replied_at")
            val merchantRepliedAt: LocalDateTime?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val review = customerReviewService.getForAdmin(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(review.id),
            productId = requireNotNull(review.product?.id),
            customerId = review.customerId,
            rating = review.rating,
            title = review.title,
            content = review.content,
            verifiedPurchase = review.verifiedPurchase,
            status = review.status.name,
            merchantReply = review.merchantReply,
            merchantRepliedAt = review.merchantRepliedAt,
            createdAt = review.createdAt,
            updatedAt = review.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 审核评价
     * @param id 评价 ID
     * @param status 审核状态
     * @param verifiedPurchase 是否已验证购买
     * @param merchantReply 商家回复
     */
    @PutMapping("/{id}/moderation")
    fun moderateReview(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("status") status: ReviewStatus,
        @RequestParam("verified_purchase", required = false) verifiedPurchase: Boolean?,
        @RequestParam("merchant_reply", required = false) @Size(max = 1000) merchantReply: String?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            @param:JsonProperty("verified_purchase")
            val verifiedPurchase: Boolean,
            val status: String,
            @param:JsonProperty("merchant_reply")
            val merchantReply: String?,
            @param:JsonProperty("merchant_replied_at")
            val merchantRepliedAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val review = customerReviewService.moderate(id, status, verifiedPurchase, merchantReply)
            ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(review.id),
            customerId = review.customerId,
            verifiedPurchase = review.verifiedPurchase,
            status = review.status.name,
            merchantReply = review.merchantReply,
            merchantRepliedAt = review.merchantRepliedAt,
            updatedAt = review.updatedAt,
        )
        return builder.ok().data(rs).build()
    }
}
