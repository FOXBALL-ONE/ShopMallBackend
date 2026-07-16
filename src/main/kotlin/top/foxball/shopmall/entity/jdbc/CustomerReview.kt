package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * 客户对某个商品提交的评价。
 * 通过多态的 [Product] 关联任意品类的商品，并以客户 ID 标识评价作者。
 */
@Entity
@Table(
    name = "customer_reviews",
    indexes = [
        Index(name = "idx_customer_reviews_product_created", columnList = "product_id, created_at"),
        Index(name = "idx_customer_reviews_customer_created", columnList = "customer_id, created_at"),
        Index(name = "idx_customer_reviews_status_created", columnList = "status, created_at"),
    ],
)
class CustomerReview(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * 被评价的商品（任意品类）。
     * 使用延迟加载并在 JSON 响应中忽略该反向引用，避免评价列表产生循环序列化。
     */
    @get:JsonIgnore
    @field:NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    var product: Product? = null,

    /** 提交评价的客户 ID。 */
    @field:Min(1)
    @Column(name = "customer_id", nullable = false)
    var customerId: Long = 0,

    /** 客户给出的评分，仅允许 1 至 5 分。 */
    @field:Min(1)
    @field:Max(5)
    @Column(nullable = false)
    var rating: Int = 5,

    /** 可选的评价标题，用于在评价列表中快速概览。 */
    @field:Size(max = 100)
    @Column(length = 100)
    var title: String? = null,

    /** 客户填写的评价正文。 */
    @field:NotBlank
    @field:Size(max = 2_000)
    @Column(nullable = false, length = 2_000)
    var content: String = "",

    /** 是否由已完成的订单确认该客户确实购买过此商品。 */
    @Column(nullable = false)
    var verifiedPurchase: Boolean = false,

    /** 评价在前台展示前的审核状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ReviewStatus = ReviewStatus.PENDING,

    /** 可选的商家公开回复。 */
    @field:Size(max = 1_000)
    @Column(length = 1_000)
    var merchantReply: String? = null,

    /** 商家最后一次回复评价的时间。 */
    @Column
    var merchantRepliedAt: LocalDateTime? = null,

    /** 评价首次持久化时由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    /** 评价每次持久化更新后由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null,
)

/** 客户评价从提交到展示的审核状态。 */
enum class ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED,
    HIDDEN,
}
