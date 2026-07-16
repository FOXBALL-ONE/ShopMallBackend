package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.PrimaryKeyJoinColumn
import jakarta.persistence.Table

/**
 * 可销售的比基尼 SKU。
 *
 * 公共属性继承自 [Product]，本类仅描述比基尼特有的上下装尺码。
 * 每条记录代表一套确定的颜色和上下装尺码组合，库存、销量和评价由基类统一维护。
 */
@Entity
@Table(name = "bikini_suits")
@DiscriminatorValue("BIKINI")
@PrimaryKeyJoinColumn(name = "product_id")
class BikiniSuit(
    /** 比基尼上装尺码；仅销售下装或套装未拆分时可为空。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "top_size", length = 8)
    var topSize: Size? = null,

    /** 比基尼下装尺码；仅销售上装或套装未拆分时可为空。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "bottom_size", length = 8)
    var bottomSize: Size? = null,
) : Product() {
    /**
     * 上装和下装可使用的标准尺码。
     * [recommendation] 为选码参考：上装使用胸部数据，下装使用腰围和臀围数据。
     */
    enum class Size(
        val recommendation: SizeRecommendation? = null,
    ) {
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
    }
}
