package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "shopmall.order")
data class OrderProperties(
    val paymentTimeoutMinutes: Long = 30,
    val idempotencyTtlSeconds: Long = 600,
    val streamMaxLen: Long = 10_000,
    val outboxMaxAttempts: Int = 5,
    val outboxAckSlaSeconds: Long = 300,
    val outboxRetentionDays: Long = 7,
    val maxQuantityPerLine: Int = 99,
    /** 单用户两笔订单创建的最小间隔（分钟），滚动窗口。 */
    val creationWindowMinutes: Long = 10,
    /** 服务端签发幂等键的持有期限（分钟），与窗口默认同值。 */
    val idempotencyKeyTtlMinutes: Long = 10,
) {
    init {
        require(paymentTimeoutMinutes in MINIMUM_PAYMENT_TIMEOUT_MINUTES..MAXIMUM_PAYMENT_TIMEOUT_MINUTES) {
            "订单支付超时时间必须在 30 分钟到 24 小时之间"
        }
        require(creationWindowMinutes in MINIMUM_CREATION_WINDOW_MINUTES..MAXIMUM_CREATION_WINDOW_MINUTES) {
            "下单窗口时间必须在 1 分钟到 24 小时之间"
        }
        require(idempotencyKeyTtlMinutes in MINIMUM_CREATION_WINDOW_MINUTES..MAXIMUM_CREATION_WINDOW_MINUTES) {
            "幂等键有效期必须在 1 分钟到 24 小时之间"
        }
    }

    private companion object {
        const val MINIMUM_PAYMENT_TIMEOUT_MINUTES = 30L
        const val MAXIMUM_PAYMENT_TIMEOUT_MINUTES = 24 * 60L
        const val MINIMUM_CREATION_WINDOW_MINUTES = 1L
        const val MAXIMUM_CREATION_WINDOW_MINUTES = 24 * 60L
    }
}
