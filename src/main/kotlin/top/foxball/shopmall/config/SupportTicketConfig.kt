package top.foxball.shopmall.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** 注册工单安全与防滥用配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupportTicketProperties::class)
class SupportTicketConfig
