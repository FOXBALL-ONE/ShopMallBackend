package top.foxball.shopmall.entity.jdbc

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
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size as ValidationSize
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 可销售的一件式泳衣 SKU。
 * 每条记录代表一种确定的颜色和尺码组合，并独立维护库存、销量、版型及商品素材。
 */
@Entity
@Table(
    name = "one_piece_suits",
    indexes = [
        Index(name = "idx_one_piece_suits_status", columnList = "status"),
        Index(name = "idx_one_piece_suits_created_at", columnList = "created_at"),
    ],
)
class OnePieceSuit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 面向消费者展示的商品名称。 */
    @field:NotBlank
    @field:ValidationSize(max = 200)
    @Column(nullable = false, length = 200)
    var name: String = "",

    /** 此 SKU 的标准尺码；一件式泳衣使用单一尺码覆盖上下身。 */
    @field:NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var size: Size? = null,

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

    /** 胸部支撑程度；无明显支撑结构时可为空。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "support_level", length = 16)
    var supportLevel: SupportLevel? = null,

    /** 下装区域的覆盖程度。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var coverage: Coverage? = null,

    /** 躯干长度版型，用于帮助不同身高和身材比例的客户选码。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "torso_fit", length = 16)
    var torsoFit: TorsoFit? = null,

    /** 泳衣领口设计。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    var neckline: Neckline? = null,

    /** 泳衣后背设计。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "back_style", length = 24)
    var backStyle: BackStyle? = null,

    /** 是否包含腹部塑形或支撑结构。 */
    @Column(name = "tummy_control", nullable = false)
    var tummyControl: Boolean = false,

    /** 是否配有可拆卸胸垫。 */
    @Column(name = "removable_padding", nullable = false)
    var removablePadding: Boolean = false,

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
        name = "one_piece_suit_highlights",
        joinColumns = [JoinColumn(name = "one_piece_suit_id", nullable = false)],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "highlight", nullable = false, length = 255)
    var highlight: MutableList<String> = mutableListOf(),

    /** 商品图片 URL，按排序值确定展示顺序。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "one_piece_suit_images",
        joinColumns = [JoinColumn(name = "one_piece_suit_id", nullable = false)],
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
        name = "one_piece_suit_design_extras",
        joinColumns = [JoinColumn(name = "one_piece_suit_id", nullable = false)],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "detail", nullable = false, length = 255)
    var designAndExtras: MutableList<String> = mutableListOf(),

    /** 洗护说明，按排序值展示。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "one_piece_suit_care_instructions",
        joinColumns = [JoinColumn(name = "one_piece_suit_id", nullable = false)],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "instruction", nullable = false, length = 255)
    var careInstructions: MutableList<String> = mutableListOf(),

    /** 已审核评价的平均评分；由服务层在评价变化后重新计算。 */
    @field:DecimalMin("0.0")
    @field:DecimalMax("5.0")
    @Column
    var score: Float? = null,

    /** 商品标签；标签由标签目录独立维护，因此不级联持久化或删除。 */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "one_piece_suit_tags",
        joinColumns = [JoinColumn(name = "one_piece_suit_id", nullable = false)],
        inverseJoinColumns = [JoinColumn(name = "tag_id", nullable = false)],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_one_piece_suit_tags_suit_tag",
                columnNames = ["one_piece_suit_id", "tag_id"],
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

    /**
     * 一件式泳衣可使用的标准尺码。
     * [recommendation] 是品牌尺码表提供的选码参考，不参与数据库枚举值的持久化。
     */
    enum class Size(
        val recommendation: BikiniSuitSizeRecommendation? = null,
    ) {
        XXS,
        XS,
        S(
            recommendation = BikiniSuitSizeRecommendation(
                braSizes = listOf("32D", "34B", "34C", "36A"),
                bust = InchRange("34.5", "36.0"),
                underbust = InchRange("28.0", "31.0"),
                waist = InchRange("27.0", "28.5"),
                hip = InchRange("37.0", "38.5"),
                torso = InchRange("61.0", "61.5"),
            ),
        ),
        M,
        L,
        XL,
        XXL,
        XXXL,
        XXXXL,
        XXXXXL,
    }

    /** 胸部支撑的相对程度。 */
    enum class SupportLevel {
        LIGHT,
        MEDIUM,
        HIGH,
    }

    /** 下装区域的相对覆盖程度。 */
    enum class Coverage {
        CHEEKY,
        MODERATE,
        FULL,
    }

    /** 适配不同躯干长度的版型。 */
    enum class TorsoFit {
        SHORT,
        REGULAR,
        LONG,
    }

    /** 常见的一件式泳衣领口设计。 */
    enum class Neckline {
        SCOOP,
        V_NECK,
        HALTER,
        BANDEAU,
        ONE_SHOULDER,
        HIGH_NECK,
    }

    /** 常见的一件式泳衣后背设计。 */
    enum class BackStyle {
        OPEN_BACK,
        CROSS_BACK,
        SCOOP_BACK,
        ZIP_BACK,
        FULL_BACK,
    }
}
