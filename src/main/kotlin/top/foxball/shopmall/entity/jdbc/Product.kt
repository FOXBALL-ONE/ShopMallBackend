package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import jakarta.validation.constraints.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime
import jakarta.validation.constraints.Size as ValidationSize

/**
 * 所有可销售商品的抽象基类。
 *
 * 采用 JOINED 继承：公共字段、媒体素材、标签与客户评价统一落在 products 表，
 * 各品类子表（bikini_suits、one_piece_suits、dresses、cover_ups 等）只保存该品类特有的属性，
 * 并通过 product_type 鉴别列区分。每条记录代表一种确定的颜色与尺码组合，独立维护库存、销量和评价。
 *
 * 统一根实体便于后续订单与库存管理以单一外键引用任意品类商品。
 */
@Entity
@Table(
    name = "products",
    indexes = [
        Index(name = "idx_products_status", columnList = "status"),
        Index(name = "idx_products_created_at", columnList = "created_at"),
        Index(name = "idx_products_product_type", columnList = "product_type"),
    ],
)
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "product_type", discriminatorType = DiscriminatorType.STRING, length = 31)
abstract class Product(
    /** 商品 SKU 的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 面向消费者展示的商品名称。 */
    @field:NotBlank
    @field:ValidationSize(max = 200)
    @Column(nullable = false, length = 200)
    var name: String = "",

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
        name = "products_highlights",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [
            Index(name = "idx_products_highlights_product_id", columnList = "product_id"),
        ],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "highlight", nullable = false, length = 255)
    var highlight: MutableList<String> = mutableListOf(),

    /** 商品图片 URL，按排序值确定展示顺序。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "products_images",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [
            Index(name = "idx_products_images_product_id", columnList = "product_id"),
        ],
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
        name = "products_design_extras",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [
            Index(name = "idx_products_design_extras_product_id", columnList = "product_id"),
        ],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "detail", nullable = false, length = 255)
    var designAndExtras: MutableList<String> = mutableListOf(),

    /** 洗护说明，按排序值展示。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "products_care_instructions",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [
            Index(name = "idx_products_care_instructions_product_id", columnList = "product_id"),
        ],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "instruction", nullable = false, length = 255)
    var careInstructions: MutableList<String> = mutableListOf(),

    /** 商品收到的客户评价；评价持有外键，避免额外的关联表。 */
    @get:JsonIgnore
    @OneToMany(
        mappedBy = "product",
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
        name = "products_tags",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        inverseJoinColumns = [JoinColumn(name = "tag_id", nullable = false)],
        indexes = [
            Index(name = "idx_products_tags_tag_id", columnList = "tag_id"),
        ],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_products_tags_product_tag",
                columnNames = ["product_id", "tag_id"],
            ),
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
}
