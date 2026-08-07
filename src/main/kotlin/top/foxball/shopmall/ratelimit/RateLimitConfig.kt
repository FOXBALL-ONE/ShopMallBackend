package top.foxball.shopmall.ratelimit

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Registers global API rate-limit configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfig
