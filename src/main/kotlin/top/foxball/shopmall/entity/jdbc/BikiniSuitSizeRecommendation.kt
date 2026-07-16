package top.foxball.shopmall.entity.jdbc

import java.math.BigDecimal

/**
 * 比基尼的结构化选码建议。
 * 上装主要参考文胸尺码、胸围和下胸围，下装主要参考腰围和臀围；所有身体尺寸均使用英寸。
 */
data class BikiniSuitSizeRecommendation(
    /** 推荐的完整文胸尺码，由下胸围数字与罩杯字母组成。 */
    val braSizes: List<String>,
    val bust: InchRange,
    val underbust: InchRange,
    val waist: InchRange,
    val hip: InchRange,
    val torso: InchRange,
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
