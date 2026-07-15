package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size as ValidationSize
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 可销售的比基尼 SKU。
 * 每条记录代表一套确定的颜色和上下装尺码组合，并独立维护库存、销量和客户评价。
 */
@Entity
@Table(
    name = "swimwear",
    indexes = [
        Index(name = "idx_swimwear_status", columnList = "status"),
        Index(name = "idx_swimwear_created_at", columnList = "created_at"),
    ],
)
class BikiniSuit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 面向消费者展示的商品名称。 */
    @field:NotBlank
    @field:ValidationSize(max = 200)
    @Column(nullable = false, length = 200)
    var name: String = "",

    /** 比基尼上装尺码；仅销售下装或套装未拆分时可为空。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "top_size", length = 8)
    var topSize: Size? = null,

    /** 比基尼下装尺码；仅销售上装或套装未拆分时可为空。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "bottom_size", length = 8)
    var bottomSize: Size? = null,

    /** 此 SKU 面向消费者展示的颜色。 */
    @field:NotBlank
    @field:ValidationSize(max = 50)
    @Column(nullable = false, length = 50)
    var color: String = "",

    /** 当前销售单价；保留两位小数且必须大于零。 */
    @field:DecimalMin("0.00", inclusive = false)
    @field:Digits(integer = 8, fraction = 2)
    @Column(nullable = false, precision = 10, scale = 2)
    var price: BigDecimal = BigDecimal.ZERO,

    /** 仓库可售库存，不允许为负数。 */
    @field:Min(0)
    @Column(name = "warehouse_volume", nullable = false)
    var warehouseVolume: Int = 0,

    /** 已完成销售的累计件数，不允许为负数。 */
    @field:Min(0)
    @Column(name = "sales_volume", nullable = false)
    var salesVolume: Int = 0,

    /** 商品首次持久化时由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    /** 商品每次持久化更新后由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    /** 商品在前台的销售状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.ACTIVE,

    /** 商品卖点，按排序值展示。 */
    @field:ValidationSize(max = 10)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "swimwear_highlights",
        joinColumns = [JoinColumn(name = "swimwear_id", nullable = false)],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "highlight", nullable = false, length = 255)
    var highlight: MutableList<String> = mutableListOf(),

    /** 商品图片 URL，按排序值确定展示顺序。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "swimwear_images",
        joinColumns = [JoinColumn(name = "swimwear_id", nullable = false)],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "image_url", nullable = false, length = 512)
    var images: MutableList<String> = mutableListOf(),

    /** 简要说明商品的版型和穿着感受。 */
    @field:ValidationSize(max = 255)
    @Column(name = "fit_sense", length = 255)
    var fitSense: String? = null,

    /** 商品的完整详情描述。 */
    @field:ValidationSize(max = 4_000)
    @Column(length = 4_000)
    var description: String? = null,

    /** 设计细节及附加配件，按排序值展示。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "swimwear_design_extras",
        joinColumns = [JoinColumn(name = "swimwear_id", nullable = false)],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "detail", nullable = false, length = 255)
    var designAndExtras: MutableList<String> = mutableListOf(),

    /** 洗护说明，按排序值展示。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "swimwear_care_instructions",
        joinColumns = [JoinColumn(name = "swimwear_id", nullable = false)],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "instruction", nullable = false, length = 255)
    var careInstructions: MutableList<String> = mutableListOf(),

    /** 商品收到的客户评价；评价持有外键，避免额外的关联表。 */
    @OneToMany(
        mappedBy = "bikiniSuit",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.PERSIST, CascadeType.MERGE],
    )
    var customerReviews: MutableList<CustomerReview> = mutableListOf(),

    /** 已审核评价的平均评分；由服务层在评价变化后重新计算。 */
    @field:DecimalMin("0.0")
    @field:DecimalMax("5.0")
    @Column
    var score: Float? = null,

    /** 商品标签；标签由标签目录独立维护，因此不级联持久化或删除。 */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "swimwear_tags",
        joinColumns = [JoinColumn(name = "swimwear_id", nullable = false)],
        inverseJoinColumns = [JoinColumn(name = "tag_id", nullable = false)],
        uniqueConstraints = [
            UniqueConstraint(name = "uk_swimwear_tags_swimwear_tag", columnNames = ["swimwear_id", "tag_id"]),
        ],
    )
    var tags: MutableSet<Tag> = linkedSetOf(),
) {
    /** 商品在前台的销售生命周期状态。 */
    enum class Status {
        ACTIVE,
        INACTIVE,
        DELETED,
    }

    /** 上装和下装可使用的标准尺码。 */
    enum class Size {
        S,
        M,
        L,
        XL,
        XXL,
        XXXL,
        XXXXL,
    }
}
