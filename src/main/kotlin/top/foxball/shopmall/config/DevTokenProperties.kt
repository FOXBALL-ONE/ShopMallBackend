package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 开发环境固定 JWT 令牌配置（`shopmall.security.jwt.dev.fixed-token.*`）。
 *
 * 开启后，[DevTokenManager] 会在启动时：
 *  1. 按 [DefaultAdminProperties] 确保一个默认管理员存在；
 *  2. 用该管理员的数据库 userId 作为 `sub`，签发一张 jti = [jti] 的固定令牌并打印到日志。
 *
 * [top.foxball.shopmall.authentication.JwtAuthenticationFilter] 识别到该 jti（且 `sub`
 * 与启动期解析的管理员 userId 一致）时，跳过 Redis 白名单与 User-Agent 绑定直接放行——
 * 方便本地用 curl/Postman 粘贴调试。令牌仍需通过 HS256 验签，故泄漏密钥才会被伪造。
 *
 * 安全须知：
 * - [enabled] 默认 `false`，**生产环境绝不开启**；仅本地/测试通过 `DEV_FIXED_TOKEN_ENABLED=true` 打开。
 * - 开启同时会落库一个默认管理员（账号/密码/邮箱见 [DefaultAdminProperties]），同样仅限本地。
 * - 有效期默认 10 年；如需轮换，改 [jti] 并重启即可。
 *
 * @param enabled 是否启用固定令牌旁路（默认关闭）。
 * @param jti 固定令牌的 jti；生成端与识别端读同一值，是判定"固定令牌"的钥匙。
 * @param ttlSeconds 固定令牌有效期（秒），默认 10 年。
 */
@ConfigurationProperties(prefix = "shopmall.security.jwt.dev.fixed-token")
data class DevTokenProperties(
    val enabled: Boolean = false,
    val jti: String = "00000000-0000-0000-0000-000000000000",
    val ttlSeconds: Long = 31_536_000L,
)
