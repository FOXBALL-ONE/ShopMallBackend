package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 开发环境默认管理员配置（`shopmall.security.jwt.dev.default-admin.*`）。
 *
 * 仅当 [DevTokenProperties.enabled] 为 true 时生效：每次启动按此配置确保一个管理员存在，
 * 供固定 JWT 令牌绑定（见 [DevTokenManager]）。**生产环境绝不启用**——默认账号/密码可被轻易猜中。
 *
 * - 已存在同名用户时不会重建，但会把密码、角色（ADMIN）、启用状态、邮箱对齐到配置，
 *   保证 dev 每次启动都能用配置密码以管理员身份登录。
 * - [password] 为明文，落库前由 [org.springframework.security.crypto.password.PasswordEncoder] 哈希。
 *
 * 与其它配置一致：真实值由 `application.yaml` 的 `${DEV_ADMIN_*:默认值}` 占位符从 `.env` / OS 环境变量注入。
 *
 * @param username 登录用户名（全局唯一）。
 * @param password 明文密码（落库前哈希）。
 * @param email 邮箱（全局唯一；dev 默认 "admin" 并非真实邮箱，仅作占位）。
 */
@ConfigurationProperties(prefix = "shopmall.security.jwt.dev.default-admin")
data class DefaultAdminProperties(
    val username: String = "admin",
    val password: String = "admin",
    val email: String = "admin",
)
