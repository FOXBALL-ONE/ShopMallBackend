package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 邮件验证码配置（shopmall.mail.verification.*）。
 *
 * - [ttlSeconds] 验证码有效期（默认 300s = 5 分钟）
 * - [codeLength] 验证码位数（默认 6 位纯数字）
 * - [sendIntervalSeconds] 同一邮箱两次发送的最小间隔（防轰炸，默认 60s）
 * - [dailyLimit] 同一邮箱每日发送上限（默认 10 次）
 * - [maxAttempts] 单个验证码允许的最大校验失败次数（防暴力枚举，默认 5 次）
 * - [ipHourlyLimit] 单一 IP 每小时发送验证码的上限（防跨邮箱轰炸，默认 30 次）
 * - [from] 发件人地址，默认回退到 spring.mail.username
 * - [subjectPrefix] 邮件主题前缀
 */
@ConfigurationProperties(prefix = "shopmall.mail.verification")
data class MailProperties(
    val ttlSeconds: Long = 300L,
    val codeLength: Int = 6,
    val sendIntervalSeconds: Long = 60L,
    val dailyLimit: Long = 10L,
    val maxAttempts: Int = 5,
    val ipHourlyLimit: Long = 30L,
    val from: String = "",
    val subjectPrefix: String = "ShopMall",
)
