package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.PrimaryKeyJoinColumn
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size as ValidationSize

/**
 * 可销售的连衣裙 SKU。
 *
 * 公共属性继承自 [Product]，本类仅描述连衣裙特有的尺码、长度、廓形与结构属性。
 * 每条记录代表一种确定的颜色和尺码组合。
 */
@Entity
@Table(name = "dresses")
@DiscriminatorValue("DRESS")
@PrimaryKeyJoinColumn(name = "product_id")
class Dress(
    /** 此 SKU 的标准尺码。 */
    @field:NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var size: Size? = null,

    /** 连衣裙长度。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var length: Length? = null,

    /** 连衣裙廓形/版型。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    var silhouette: Silhouette? = null,

    /** 领口设计。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    var neckline: Neckline? = null,

    /** 袖型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sleeve_type", length = 24)
    var sleeveType: SleeveType? = null,

    /** 面料说明（自由文本）。 */
    @field:ValidationSize(max = 100)
    @Column(length = 100)
    var fabric: String? = null,
) : Product() {
    /**
     * 连衣裙可使用的标准尺码。
     * [recommendation] 是品牌尺码表提供的选码参考（主要参考胸围、腰围和臀围），不参与数据库枚举值的持久化。
     */
    enum class Size(
        val recommendation: SizeRecommendation? = null,
    ) {
        XS,
        S(
            recommendation = SizeRecommendation(
                bust = InchRange("34.5", "36.0"),
                waist = InchRange("27.0", "28.5"),
                hip = InchRange("37.0", "38.5"),
            ),
        ),
        M,
        L,
        XL,
        XXL,
    }

    /** 连衣裙长度。 */
    enum class Length {
        MAXI,
        MIDI,
        MINI,
    }

    /** 连衣裙廓形。 */
    enum class Silhouette {
        SLIP,
        A_LINE,
        SHIRT,
        WRAP,
        SHIFT,
        BODYCON,
        SMOKED,
    }

    /** 常见的连衣裙领口设计。 */
    enum class Neckline {
        SCOOP,
        V_NECK,
        SWEETHEART,
        HALTER,
        OFF_SHOULDER,
        ROUND,
        SQUARE,
    }

    /** 常见的袖型。 */
    enum class SleeveType {
        SLEEVELESS,
        SHORT,
        CAP,
        THREE_QUARTER,
        LONG,
        PUFF,
        BELL,
    }
}
