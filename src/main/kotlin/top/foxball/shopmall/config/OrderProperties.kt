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
) {
    init {
        require(paymentTimeoutMinutes in MINIMUM_PAYMENT_TIMEOUT_MINUTES..MAXIMUM_PAYMENT_TIMEOUT_MINUTES) {
            "订单支付超时时间必须在 30 分钟到 24 小时之间"
        }
    }

    private companion object {
        const val MINIMUM_PAYMENT_TIMEOUT_MINUTES = 30L
        const val MAXIMUM_PAYMENT_TIMEOUT_MINUTES = 24 * 60L
    }
}
