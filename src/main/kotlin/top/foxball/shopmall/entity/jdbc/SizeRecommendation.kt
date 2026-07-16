package top.foxball.shopmall.entity.jdbc

import java.math.BigDecimal

/**
 * 结构化选码建议。
 *
 * 不同品类按需填写相关身体尺寸：上装/泳衣上装主要参考文胸尺码、胸围和下胸围；
 * 下装/泳衣下装主要参考腰围和臀围；连衣裙等合体服装一般参考胸围、腰围和臀围。
 * 罩衫等宽松服装通常不提供选码建议。所有身体尺寸均使用英寸。
 */
data class SizeRecommendation(
    /** 推荐的完整文胸尺码，由下胸围数字与罩杯字母组成；仅合体上装/泳衣填写。 */
    val braSizes: List<String>? = null,
    val bust: InchRange? = null,
    val underbust: InchRange? = null,
    val waist: InchRange? = null,
    val hip: InchRange? = null,
    val torso: InchRange? = null,
)

/** 闭区间英寸范围，最小值和最大值均属于推荐范围。 */
data class InchRange(
    val min: BigDecimal,
    val max: BigDecimal,
) {
    constructor(min: String, max: String) : this(BigDecimal(min), BigDecimal(max))

    init {
        require(min > BigDecimal.ZERO) { "尺寸范围最小值必须大于零" }
        require(max >= min) { "尺寸范围最大值不能小于最小值" }
    }
}
