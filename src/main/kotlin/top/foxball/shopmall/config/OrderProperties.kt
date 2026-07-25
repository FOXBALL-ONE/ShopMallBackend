package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "shopmall.order")
data class OrderProperties(
    val paymentTimeoutMinutes: Long = 15,
    val idempotencyTtlSeconds: Long = 600,
    val streamMaxLen: Long = 10_000,
    val outboxMaxAttempts: Int = 5,
    val outboxAckSlaSeconds: Long = 300,
    val outboxRetentionDays: Long = 7,
    val maxQuantityPerLine: Int = 99,
)
