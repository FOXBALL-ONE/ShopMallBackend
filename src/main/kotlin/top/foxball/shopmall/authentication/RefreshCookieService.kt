package top.foxball.shopmall.authentication

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import top.foxball.shopmall.config.JwtProperties
import java.time.Duration

/**
 * 刷新令牌的 HttpOnly Cookie 读写。
 *
 * 登录/续期成功时由本类写 refresh cookie（`maxAge` = refresh ttl）；登出时清 cookie
 * （value 置空、`maxAge=0`）。Cookie 字段全部来自 [JwtProperties.refresh.cookie]，
 * 详见 `docs/dual-token-auth-design.md` §6.1。
 *
 * - [JwtProperties.Cookie.path] 限定 `/api/auth`，浏览器仅访问刷新端点时回传，降低泄漏面。
 * - [JwtProperties.Cookie.secure] 生产只走 HTTPS；本地 http 调试需设 `false`。
 * - [JwtProperties.Cookie.httpOnly] JS 不可读，防 XSS 窃取。
 * - [JwtProperties.Cookie.sameSite] 同注册域用 `Lax`；跨注册域必须 `None` 且 `secure=true`。
 * - [JwtProperties.Cookie.domain] 留空=不写 domain（浏览器用当前 host）；同注册域多子域可配。
 */
@Component
class RefreshCookieService(
    private val properties: JwtProperties,
) {

    /** 登录/续期成功时写入 refresh cookie；maxAge = refresh ttl。 */
    fun attachRefresh(response: HttpServletResponse, jwt: String) {
        val cookie = ResponseCookie.from(properties.refresh.cookie.name, jwt)
            .httpOnly(properties.refresh.cookie.httpOnly)
            .secure(properties.refresh.cookie.secure)
            .sameSite(properties.refresh.cookie.sameSite)   // Lax / Strict / None
            .path(properties.refresh.cookie.path)            // /api/auth
            .domain(properties.refresh.cookie.domain.ifEmpty { null })  // 留空=不写 domain
            .maxAge(Duration.ofSeconds(properties.refresh.ttlSeconds))
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    /** 登出时清 cookie：value 置空、maxAge=0。 */
    fun clear(response: HttpServletResponse) {
        val cookie = ResponseCookie.from(properties.refresh.cookie.name, "")
            .httpOnly(properties.refresh.cookie.httpOnly)
            .secure(properties.refresh.cookie.secure)
            .sameSite(properties.refresh.cookie.sameSite)
            .path(properties.refresh.cookie.path)
            .domain(properties.refresh.cookie.domain.ifEmpty { null })
            .maxAge(Duration.ZERO)
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }
}
