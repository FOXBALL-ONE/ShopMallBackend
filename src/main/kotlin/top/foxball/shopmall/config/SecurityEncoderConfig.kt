package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/** 注册全局 Argon2 密码编码器。 */
@Configuration
@EnableConfigurationProperties(Argon2PasswordEncoderProperties::class)
class SecurityEncoderConfig(
    private val properties: Argon2PasswordEncoderProperties,
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder(
        properties.saltLength,
        properties.hashLength,
        properties.parallelism,
        properties.memory,
        properties.iterations,
    )
}

/** Argon2 参数；调整时须兼顾登录延迟、内存预算与已有密码哈希的兼容性。 */
@ConfigurationProperties(prefix = "shopmall.security.password.argon2")
data class Argon2PasswordEncoderProperties(
    val saltLength: Int = 16,
    val hashLength: Int = 32,
    val parallelism: Int = 1,
    val memory: Int = 16_384,
    val iterations: Int = 2,
)
