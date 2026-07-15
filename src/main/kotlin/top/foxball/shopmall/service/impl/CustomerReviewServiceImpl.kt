package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.ReviewStatus
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.repository.BikiniSuitRepository
import top.foxball.shopmall.repository.CustomerReviewRepository
import top.foxball.shopmall.service.CustomerReviewService
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class CustomerReviewServiceImpl(
    private val customerReviewRepository: CustomerReviewRepository,
    private val bikiniSuitRepository: BikiniSuitRepository,
) : CustomerReviewService {
    override fun listPublishedByBikiniSuit(bikiniSuitId: Long): List<CustomerReview> =
        customerReviewRepository.findAllByBikiniSuit_IdAndStatusOrderByCreatedAtDesc(
            bikiniSuitId,
            ReviewStatus.APPROVED,
        )

    override fun getPublished(id: Long): CustomerReview? =
        customerReviewRepository.findById(id).orElse(null)?.takeIf { it.status == ReviewStatus.APPROVED }

    override fun listForAdmin(): List<CustomerReview> = customerReviewRepository.findAllByOrderByCreatedAtDesc()

    override fun getForAdmin(id: Long): CustomerReview? = customerReviewRepository.findById(id).orElse(null)

    @Transactional
    override fun create(customerId: Long, bikiniSuitId: Long, source: CustomerReview): CustomerReview? {
        val bikiniSuit = bikiniSuitRepository.findByIdAndStatus(bikiniSuitId, BikiniSuit.Status.ACTIVE) ?: return null
        val review = CustomerReview(
            bikiniSuit = bikiniSuit,
            customerId = customerId,
            rating = source.rating,
            title = source.title,
            content = source.content,
            status = ReviewStatus.PENDING,
            verifiedPurchase = false,
        )
        return customerReviewRepository.save(review)
    }

    @Transactional
    override fun updateByCustomer(id: Long, customerId: Long, source: CustomerReview): CustomerReview? {
        val review = customerReviewRepository.findById(id).orElse(null) ?: return null
        if (review.customerId != customerId) {
            throw ForbiddenException("只能修改自己的评价")
        }
        review.rating = source.rating
        review.title = source.title
        review.content = source.content
        review.status = ReviewStatus.PENDING
        return review
    }

    @Transactional
    override fun moderate(
        id: Long,
        status: ReviewStatus,
        verifiedPurchase: Boolean?,
        merchantReply: String?,
    ): CustomerReview? {
        val review = customerReviewRepository.findById(id).orElse(null) ?: return null
        review.status = status
        verifiedPurchase?.let { review.verifiedPurchase = it }
        merchantReply?.let {
            review.merchantReply = it
            review.merchantRepliedAt = LocalDateTime.now()
        }
        return review
    }

    @Transactional
    override fun delete(id: Long, customerId: Long, isAdmin: Boolean): Boolean? {
        val review = customerReviewRepository.findById(id).orElse(null) ?: return null
        if (!isAdmin && review.customerId != customerId) {
            throw ForbiddenException("只能删除自己的评价")
        }
        customerReviewRepository.delete(review)
        return true
    }
}
