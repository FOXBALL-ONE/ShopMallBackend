package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.ReviewStatus

interface CustomerReviewService {
    fun listPublishedByProduct(productId: Long): List<CustomerReview>

    fun getPublished(id: Long): CustomerReview?

    fun listForAdmin(): List<CustomerReview>

    fun getForAdmin(id: Long): CustomerReview?

    fun create(customerId: Long, productId: Long, source: CustomerReview): CustomerReview?

    fun updateByCustomer(id: Long, customerId: Long, source: CustomerReview): CustomerReview?

    fun moderate(
        id: Long,
        status: ReviewStatus,
        verifiedPurchase: Boolean?,
        merchantReply: String?,
    ): CustomerReview?

    fun delete(id: Long, customerId: Long, isAdmin: Boolean): Boolean?
}
