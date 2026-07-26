package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.authentication.RefreshCookieService
import top.foxball.shopmall.config.JwtProperties
import top.foxball.shopmall.service.AuthService
import top.foxball.shopmall.service.MailService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 认证
 */
@Validated
@RestController
class AuthController(
    private val authService: AuthService,
    private val mailService: MailService,
    private val userService: UserService,
    private val loginTokenAuthentication: LoginTokenAuthentication,
    private val refreshCookieService: RefreshCookieService,
    private val jwtProperties: JwtProperties,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 登录
     * @param identifier 用户名或邮箱
     * @param password 密码
     */
    @PostMapping("/api/auth/login")
    fun login(
        @RequestParam("identifier") @NotBlank identifier: String,
        @RequestParam("password") @NotBlank password: String,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Response> {
        data class LimitData(
            val tunnel: Long?,
            val inbound: Long,
            val outbound: Long,
        )

        data class GroupData(
            val id: Long,
            val name: String,
        )

        data class UserData(
            val username: String,
            val email: String,
            val limit: LimitData,
            val avatar: String,
            val traffic: BigDecimal,
            @param:JsonProperty("register_time")
            val registerTime: LocalDateTime,
            val group: GroupData,
        )

        data class Response(
            @param:JsonProperty("access_token")
            val accessToken: String,
            @param:JsonProperty("expires_in")
            val expiresIn: Long,
            @param:JsonProperty("user_id")
            val userId: Long,
            @param:JsonProperty("frp_token")
            val frpToken: String,
            @param:JsonProperty("user_info")
            val userInfo: UserData,
        )

        val result = authService.login(identifier, password, userAgent.orEmpty(), clientIp(request))
        val loginResponse = result.response
        if (result.state != LoginTokenAuthentication.LoginResult.State.SUCCESS || loginResponse == null) {
            return builder.forbidden().message("登录被拒绝").build()
        }
        result.refreshJwt?.let { refreshCookieService.attachRefresh(response, it) }

        val userInfo = loginResponse.userInfo
        val rs = Response(
            accessToken = loginResponse.accessToken,
            expiresIn = loginResponse.expiresIn,
            userId = loginResponse.userId,
            frpToken = loginResponse.frpToken,
            userInfo = UserData(
                username = userInfo.username,
                email = userInfo.email,
                limit = LimitData(userInfo.limit.tunnel, userInfo.limit.inbound, userInfo.limit.outbound),
                avatar = userInfo.avatar,
                traffic = userInfo.traffic,
                registerTime = userInfo.registerTime,
                group = GroupData(userInfo.group.id, userInfo.group.name),
            ),
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 刷新访问令牌
     */
    @PostMapping("/api/auth/refresh")
    fun refresh(
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("access_token")
            val accessToken: String,
            @param:JsonProperty("expires_in")
            val expiresIn: Long,
        )

        val refreshJwt = readRefreshCookie(request)
            ?: return builder.unauthorized().message("未提供刷新令牌").build()
        val result = loginTokenAuthentication.refresh(refreshJwt, userAgent.orEmpty())
        refreshCookieService.attachRefresh(response, result.refreshJwt)
        val rs = Response(result.accessToken, result.expiresIn)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 退出登录
     */
    @PostMapping("/api/auth/logout")
    fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Response> {
        loginTokenAuthentication.logout(readRefreshCookie(request))
        refreshCookieService.clear(response)
        return builder.ok().message("已退出").build()
    }

    /**
     * @api 发送注册验证码
     * @param email 注册邮箱
     */
    @PostMapping("/api/auth/verification-code")
    fun sendRegistrationCode(
        @RequestParam("email") @NotBlank @Email email: String,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Response> {
        mailService.sendCode(email, userAgent.orEmpty(), null, clientIp(request))
        return builder.ok().message("验证码已发送").build()
    }

    /**
     * @api 发送修改密码验证码
     */
    @PostMapping("/api/auth/password-code")
    fun sendPasswordChangeCode(
        @AuthenticationPrincipal userId: Long,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Response> {
        val email = userService.getUserById(userId)?.email ?: return builder.notFound().build()
        mailService.sendCode(email, userAgent.orEmpty(), userId, clientIp(request))
        return builder.ok().message("验证码已发送").build()
    }

    /**
     * @api 修改密码
     * @param currentPassword 当前密码
     * @param newPassword 新密码
     * @param verificationCode 邮箱验证码
     */
    @PostMapping("/api/auth/change-password")
    fun changePassword(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("current_password") @NotBlank currentPassword: String,
        @RequestParam("new_password") @NotBlank @Size(min = 8, max = 72) newPassword: String,
        @RequestParam("verification_code") @NotBlank verificationCode: String,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("password_changed")
            val passwordChanged: Boolean,
            @param:JsonProperty("sessions_revoked")
            val sessionsRevoked: Boolean,
        )

        authService.changePassword(userId, currentPassword, newPassword, verificationCode, userAgent.orEmpty())
        val rs = Response(passwordChanged = true, sessionsRevoked = true)
        return builder.ok().data(rs).build()
    }

    private fun readRefreshCookie(request: HttpServletRequest): String? {
        val cookies = request.cookies ?: return null
        val name = jwtProperties.refresh.cookie.name
        return cookies.firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() }
    }

    private fun clientIp(request: HttpServletRequest): String {
        val forwardedFor = request.getHeader("X-Forwarded-For")
        if (!forwardedFor.isNullOrBlank()) return forwardedFor.substringBefore(",").trim()
        return request.remoteAddr
    }
}
