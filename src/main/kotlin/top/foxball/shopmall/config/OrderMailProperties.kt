package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 订单付款确认邮件的投递配置（shopmall.mail.order.*）。 */
@ConfigurationProperties(prefix = "shopmall.mail.order")
data class OrderMailProperties(
    /** 发件人地址，留空时回退到 spring.mail.username。 */
    val from: String = "",
    /** 付款确认邮件主题的品牌前缀。 */
    val subjectPrefix: String = "PELISSA",
)
