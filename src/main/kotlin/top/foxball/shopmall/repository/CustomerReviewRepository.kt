package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.ReviewStatus

interface CustomerReviewRepository : JpaRepository<CustomerReview, Long> {
    fun findAllByProduct_IdAndStatusOrderByCreatedAtDesc(
        productId: Long,
        status: ReviewStatus,
    ): List<CustomerReview>

    fun findAllByOrderByCreatedAtDesc(): List<CustomerReview>

    @Modifying(flushAutomatically = true)
    @Query("delete from CustomerReview r where r.customerId in :customerIds")
    fun deleteAllByCustomerIdIn(@Param("customerIds") customerIds: Collection<Long>): Int
}
