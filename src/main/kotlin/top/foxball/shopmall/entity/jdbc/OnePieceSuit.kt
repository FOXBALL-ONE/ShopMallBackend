package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.PrimaryKeyJoinColumn
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull

/**
 * 可销售的一件式泳衣 SKU。
 *
 * 公共属性继承自 [Product]，本类仅描述一件式泳衣特有的尺码、版型与结构属性。
 * 每条记录代表一种确定的颜色和尺码组合。
 */
@Entity
@Table(name = "one_piece_suits")
@DiscriminatorValue("ONE_PIECE")
@PrimaryKeyJoinColumn(name = "product_id")
class OnePieceSuit(
    /** 此 SKU 的标准尺码；一件式泳衣使用单一尺码覆盖上下身。 */
    @field:NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var size: Size? = null,

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
) : Product() {
    /**
     * 一件式泳衣可使用的标准尺码。
     * [recommendation] 是品牌尺码表提供的选码参考，不参与数据库枚举值的持久化。
     */
    enum class Size(
        val recommendation: SizeRecommendation? = null,
    ) {
        XXS,
        XS,
        S(
            recommendation = SizeRecommendation(
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
