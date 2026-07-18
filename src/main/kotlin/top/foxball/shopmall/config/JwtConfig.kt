package top.foxball.shopmall.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import top.foxball.shopmall.authentication.JwtService

/** 装配 [JwtService]（密钥来自 [JwtProperties]，可被环境变量覆盖）并注册 dev 固定令牌相关配置。 */
@Configuration
@EnableConfigurationProperties(
    JwtProperties::class,
    DevTokenProperties::class,
    DefaultAdminProperties::class,
)
class JwtConfig {
    @Bean
    fun jwtService(properties: JwtProperties): JwtService = JwtService(properties.secret)
}
