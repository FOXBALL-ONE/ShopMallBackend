package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.authentication.RefreshCookieService
import top.foxball.shopmall.config.JwtProperties
import top.foxball.shopmall.service.AuthService
import top.foxball.shopmall.service.MailService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.shared.Response as ApiResponse

/** 认证相关 HTTP 接口：登录、续期、登出、邮箱验证码签发与当前会话的密码修改。 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val mailService: MailService,
    private val userService: UserService,
    private val loginTokenAuthentication: LoginTokenAuthentication,
    private val refreshCookieService: RefreshCookieService,
    private val jwtProperties: JwtProperties,
    private val builder: ResponseBuilder,
) {

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        http: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<ApiResponse> {
        // UA 必须与 JwtAuthenticationFilter / 刷新端点校验来源一致（请求头），直接取头，
        // 避免 body 指定的 UA 与后续请求的请求头不一致，导致令牌无法通过 UA 绑定校验
        val userAgent = http.getHeader("User-Agent").orEmpty()
        val ip = clientIp(http)
        val result = authService.login(request.identifier, request.password, userAgent, ip)

        // GROUP_NOT_ALLOWED 等非成功分支暂无响应体，回 403
        if (result.state != LoginTokenAuthentication.LoginResult.State.SUCCESS || result.response == null) {
            return builder.forbidden().message("登录被拒绝").build()
        }
        // 登录成功：refresh 经 HttpOnly Cookie 下发，access 进响应体
        result.refreshJwt?.let { refreshCookieService.attachRefresh(response, it) }
        return builder.ok().data(result.response).build()
    }

    /**
     * 续期：读 HttpOnly Cookie 里的 refresh（无 Bearer，permitAll），原子轮换后回新 access + 滚动 cookie。
     *
     * refresh 失效 / 复用撤销 / 用户禁用由 service 抛 [top.foxball.shopmall.handler.TokenInvalidException]
     * 或 [top.foxball.shopmall.handler.UserDisabledException]，经全局异常处理回 401/403。
     */
    @PostMapping("/refresh")
    fun refresh(
        http: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<ApiResponse> {
        val refreshJwt = readRefreshCookie(http) ?: return builder.unauthorized().message("未提供刷新令牌").build()
        val userAgent = http.getHeader("User-Agent").orEmpty()
        val result = loginTokenAuthentication.refresh(refreshJwt, userAgent)
        // 滚动 refresh cookie（新 refresh）
        refreshCookieService.attachRefresh(response, result.refreshJwt)
        // refresh 不进响应体；access 字段命名与登录响应对齐（snake_case），供前端续期重放
        return builder.ok().data(RefreshResponse(result.accessToken, result.expiresIn)).build()
    }

    /**
     * 登出：permitAll（access 过期也要能登出）。有 cookie 则撤销对应 refresh 记录并清 cookie；
     * 无 cookie 幂等返回成功。
     */
    @PostMapping("/logout")
    fun logout(
        http: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<ApiResponse> {
        val refreshJwt = readRefreshCookie(http)
        loginTokenAuthentication.logout(refreshJwt)
        refreshCookieService.clear(response)
        return builder.ok().message("已登出").build()
    }

    /** 注册验证码签发：匿名流程，[userId] 为 `null`（见 [MailService.sendCode]）。 */
    @PostMapping("/verification-code")
    fun sendRegistrationCode(
        @Valid @RequestBody request: SendCodeRequest,
        http: HttpServletRequest,
    ): ResponseEntity<ApiResponse> {
        mailService.sendCode(request.email, userAgent(http), null, clientIp(http))
        return builder.ok().message("验证码已发送").build()
    }

    /** 修改密码验证码签发：要求登录，向当前用户自己的邮箱发送，并绑定其 [userId]。 */
    @PostMapping("/password-code")
    fun sendPasswordChangeCode(
        @AuthenticationPrincipal userId: Long,
        http: HttpServletRequest,
    ): ResponseEntity<ApiResponse> {
        val email = userService.getUserById(userId)?.email
            ?: return builder.notFound().build()
        mailService.sendCode(email, userAgent(http), userId, clientIp(http))
        return builder.ok().message("验证码已发送").build()
    }

    @PostMapping("/change-password")
    fun changePassword(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: ChangePasswordRequest,
        http: HttpServletRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val passwordChanged: Boolean,
            val sessionsRevoked: Boolean,
        )

        authService.changePassword(
            userId,
            request.currentPassword,
            request.newPassword,
            request.verificationCode,
            userAgent(http),
        )
        val rs = Response(
            passwordChanged = true,
            sessionsRevoked = true,
        )
        return builder.ok().data(rs).build()
    }

    /** 登录凭据；`identifier` 可为用户名或邮箱，具体判定由服务层负责。 */
    data class LoginRequest(
        @field:NotBlank val identifier: String,
        @field:NotBlank val password: String,
    )

    /**
     * 续期响应：仅回新 access（refresh 走 HttpOnly Cookie，不进响应体）。
     * 字段命名与登录响应对齐（snake_case），前端按 `access_token` 续期重放。
     */
    data class RefreshResponse(
        @get:JsonProperty("access_token") val accessToken: String,
        @get:JsonProperty("expires_in") val expiresIn: Long,
    )

    /** 发送验证码请求；仅校验邮箱格式，邮箱是否可用由调用方场景决定。 */
    data class SendCodeRequest(
        @field:NotBlank
        @field:Email
        val email: String,
    )

    /** 修改密码请求；新密码长度限制与 Argon2 的输入限制保持一致，[verificationCode] 为邮箱验证码。 */
    data class ChangePasswordRequest(
        @field:NotBlank
        val currentPassword: String,

        @field:NotBlank
        @field:Size(min = 8, max = 72)
        val newPassword: String,

        @field:NotBlank
        val verificationCode: String,
    )

    /** 从请求 Cookie 中取刷新令牌；缺失返回 null。 */
    private fun readRefreshCookie(http: HttpServletRequest): String? {
        val cookies = http.cookies ?: return null
        val name = jwtProperties.refresh.cookie.name
        return cookies.firstOrNull { it.name == name }?.value?.takeIf { v -> v.isNotBlank() }
    }

    private fun userAgent(http: HttpServletRequest): String = http.getHeader("User-Agent").orEmpty()

    private fun clientIp(request: HttpServletRequest): String {
        // 优先取 X-Forwarded-For 首段（经反代/CDN 时为真实客户端 IP，需部署侧确保该头可信），
        // 否则回退到 TCP 直连地址
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.substringBefore(",").trim()
        return request.remoteAddr
    }
}
