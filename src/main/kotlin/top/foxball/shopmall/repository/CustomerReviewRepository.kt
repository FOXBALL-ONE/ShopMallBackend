package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.ReviewStatus

interface CustomerReviewRepository : JpaRepository<CustomerReview, Long> {
    fun findAllByBikiniSuit_IdAndStatusOrderByCreatedAtDesc(
        bikiniSuitId: Long,
        status: ReviewStatus,
    ): List<CustomerReview>

    fun findAllByOrderByCreatedAtDesc(): List<CustomerReview>
}
