package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * JWT 配置（`shopmall.security.jwt.*`）：双 Token（Access + Refresh）模型。
 *
 * - [secret] HS256 签名密钥，access 与 refresh 共用同一密钥，以 `typ` claim 区分；
 *   生产环境务必通过环境变量 `JWT_SECRET` 覆盖默认占位值。
 * - [access] 访问令牌：短有效期（默认 30min），无状态、仅验签名+过期+类型，泄漏窗口收敛。
 * - [refresh] 刷新令牌：长有效期（默认 7d），落 Redis 管理；原子轮换 + grace 重试 + 复用检测。
 *   详见 `docs/dual-token-auth-design.md`。
 *
 * 旧顶层 `ttl-seconds`（单 JWT，3 天）已迁移为 [access.ttlSeconds]。
 */
@ConfigurationProperties(prefix = "shopmall.security.jwt")
data class JwtProperties(
    val secret: String = "dev-secret-do-not-use-in-prod-please-override-via-JWT_SECRET",
    val access: Access = Access(),
    val refresh: Refresh = Refresh(),
) {

    /** 访问令牌：短有效期，默认 30 分钟。 */
    data class Access(
        val ttlSeconds: Long = 1800L,
    )

    /**
     * 刷新令牌：长有效期，默认 7 天。
     *
     * - [rotate] true=每次刷新签发新 refresh 并作废旧 token（推荐）；false=刷新只换 access（复用检测失效）。
     * - [reuseDetect] true=超 grace / 跨 UA 的 USED 再现即撤销整族（推荐）。
     * - [graceSeconds] USED token 在此窗口内再现视为合法重试（响应丢失重发 / 并发第二方），窗口外才判被盗。
     * - [cookie] 刷新令牌走 HttpOnly Cookie，传输安全见 [Cookie]。
     */
    data class Refresh(
        val ttlSeconds: Long = 604_800L,
        val rotate: Boolean = true,
        val reuseDetect: Boolean = true,
        val graceSeconds: Long = 30L,
        val cookie: Cookie = Cookie(),
    )

    /**
     * 刷新令牌的 Cookie 配置。
     *
     * - [path] 限定 `/api/auth`，浏览器仅访问刷新端点时回传，降低泄漏面。
     * - [secure] 生产只走 HTTPS；本地 http 调试需设 `false`，否则浏览器不回传 cookie（最易踩的坑）。
     * - [httpOnly] JS 不可读，防 XSS 窃取。
     * - [sameSite] 同注册域用 `Lax`（默认）；跨注册域（不同 eTLD+1）必须 `None` 且 [secure]=true。
     *   详见 `docs/dual-token-auth-design.md` §6.2。
     * - [domain] 留空=不写 domain（浏览器用当前 host）；同注册域多子域可配 `.shopmall.com`。
     */
    data class Cookie(
        val name: String = "refresh_token",
        val domain: String = "",
        val path: String = "/api/auth",
        val secure: Boolean = true,
        val httpOnly: Boolean = true,
        val sameSite: String = "Lax",
    )
}
