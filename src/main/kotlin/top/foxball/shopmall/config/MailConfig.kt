package top.foxball.shopmall.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 装配验证码及订单邮件模块：注册 [MailProperties] 和 [OrderMailProperties] 绑定。
 *
 * [top.foxball.shopmall.service.MailService] 的实现以 `@Service` 自动扫描；
 * `JavaMailSender` 由 spring-boot-starter-mail 按 `spring.mail.*` 自动配置，无需在此声明。
 */
@Configuration
@EnableConfigurationProperties(MailProperties::class, OrderMailProperties::class)
class MailConfig
