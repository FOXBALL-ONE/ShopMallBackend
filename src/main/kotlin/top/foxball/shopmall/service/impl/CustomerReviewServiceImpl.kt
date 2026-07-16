package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ReviewStatus
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.repository.CustomerReviewRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.CustomerReviewService
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class CustomerReviewServiceImpl(
    private val customerReviewRepository: CustomerReviewRepository,
    private val productRepository: ProductRepository,
) : CustomerReviewService {
    override fun listPublishedByProduct(productId: Long): List<CustomerReview> =
        customerReviewRepository.findAllByProduct_IdAndStatusOrderByCreatedAtDesc(
            productId,
            ReviewStatus.APPROVED,
        )

    override fun getPublished(id: Long): CustomerReview? =
        customerReviewRepository.findById(id).orElse(null)?.takeIf { it.status == ReviewStatus.APPROVED }

    override fun listForAdmin(): List<CustomerReview> = customerReviewRepository.findAllByOrderByCreatedAtDesc()

    override fun getForAdmin(id: Long): CustomerReview? = customerReviewRepository.findById(id).orElse(null)

    @Transactional
    override fun create(customerId: Long, productId: Long, source: CustomerReview): CustomerReview? {
        val product = productRepository.findByIdAndStatus(productId, Product.Status.ACTIVE) ?: return null
        val review = CustomerReview(
            product = product,
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
        val wasApproved = review.status == ReviewStatus.APPROVED
        review.rating = source.rating
        review.title = source.title
        review.content = source.content
        review.status = ReviewStatus.PENDING
        // 客户修改后评价回到待审核；若原本已通过，平均分随之变化，需重算。
        if (wasApproved) recomputeScore(review.product?.id)
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
        recomputeScore(review.product?.id)
        return review
    }

    @Transactional
    override fun delete(id: Long, customerId: Long, isAdmin: Boolean): Boolean? {
        val review = customerReviewRepository.findById(id).orElse(null) ?: return null
        if (!isAdmin && review.customerId != customerId) {
            throw ForbiddenException("只能删除自己的评价")
        }
        val productId = review.product?.id
        customerReviewRepository.delete(review)
        recomputeScore(productId)
        return true
    }

    /**
     * 重新计算某商品的已审核评价平均分并写回。
     * 评价的 product 是延迟代理，读取其 id 不会触发加载；随后按 id 查得受管实体并改写 score，由脏检查落库。
     */
    private fun recomputeScore(productId: Long?) {
        if (productId == null) return
        val product = productRepository.findById(productId).orElse(null) ?: return
        val approved = customerReviewRepository
            .findAllByProduct_IdAndStatusOrderByCreatedAtDesc(productId, ReviewStatus.APPROVED)
        product.score = if (approved.isEmpty()) null else approved.map { it.rating }.average().toFloat()
    }
}
