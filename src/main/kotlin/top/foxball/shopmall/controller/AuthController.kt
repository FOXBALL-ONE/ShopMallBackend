package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
import top.foxball.shopmall.service.PasswordResetService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder

/**
 * @folder 认证
 */
@Validated
@RestController
class AuthController(
    private val authService: AuthService,
    private val mailService: MailService,
    private val passwordResetService: PasswordResetService,
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
        data class UserData(
            val username: String,
            val email: String,
            @param:JsonProperty("first_name")
            val firstName: String,
            @param:JsonProperty("last_name")
            val lastName: String,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val role: String,
        )

        data class Response(
            @param:JsonProperty("access_token")
            val accessToken: String,
            @param:JsonProperty("expires_in")
            val expiresIn: Long,
            @param:JsonProperty("user_id")
            val userId: Long,
            @param:JsonProperty("user_info")
            val userInfo: UserData,
        )

        val result = authService.login(identifier, password, userAgent.orEmpty(), clientIp(request))
        refreshCookieService.attachRefresh(response, result.refreshJwt)

        val userInfo = result.userInfo
        val rs = Response(
            accessToken = result.accessToken,
            expiresIn = result.expiresIn,
            userId = result.userId,
            userInfo = UserData(
                username = userInfo.username,
                email = userInfo.email,
                firstName = userInfo.firstName,
                lastName = userInfo.lastName,
                avatar = userInfo.avatar,
                locale = userInfo.locale,
                currency = userInfo.currency,
                role = userInfo.role,
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
     * @api 发送当前账户邮箱验证码
     */
    @PostMapping("/api/auth/email-verification-code")
    fun sendEmailVerificationCode(
        @AuthenticationPrincipal userId: Long,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
        )

        val user = userService.getUserById(userId) ?: return builder.notFound().build()
        if (!user.emailVerified) {
            mailService.sendCode(user.email, userAgent.orEmpty(), userId, clientIp(request))
        }
        val rs = Response(emailVerified = user.emailVerified)
        return builder.ok().message(if (user.emailVerified) "邮箱已验证" else "验证码已发送").data(rs).build()
    }

    /**
     * @api 验证当前账户邮箱
     * @param verificationCode 邮箱验证码
     */
    @PostMapping("/api/auth/email-verification")
    fun verifyEmail(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("verification_code") @NotBlank @Pattern(regexp = "^\\d{6}$") verificationCode: String,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
        )

        val user = userService.getUserById(userId) ?: return builder.notFound().build()
        if (!user.emailVerified) {
            mailService.verifyCode(user.email, verificationCode, userAgent.orEmpty(), userId)
            user.emailVerified = true
            userService.updateUser(user)
        }
        val rs = Response(emailVerified = true)
        return builder.ok().message("邮箱验证成功").data(rs).build()
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

    /**
     * @api 请求密码重置邮件
     * @param email 用户注册邮箱
     */
    @PostMapping("/api/auth/password-reset/request")
    fun requestPasswordReset(
        @RequestParam("email") @NotBlank @Email @Size(max = 100) email: String,
    ): ResponseEntity<Response> {
        passwordResetService.requestReset(email)
        return builder.ok().message("如果该邮箱已注册，密码重置邮件已发送").build()
    }

    /**
     * @api 使用一次性链接重置密码
     * @param token 邮件中的一次性密码重置令牌
     * @param newPassword 新密码
     * @param confirmPassword 确认新密码
     */
    @PostMapping("/api/auth/password-reset")
    fun resetPassword(
        @RequestParam("token") @NotBlank @Size(max = 128) token: String,
        @RequestParam("new_password") @NotBlank @Size(min = 8, max = 72) newPassword: String,
        @RequestParam("confirm_password") @NotBlank @Size(min = 8, max = 72) confirmPassword: String,
        response: HttpServletResponse,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("password_reset")
            val passwordReset: Boolean,
            @param:JsonProperty("sessions_revoked")
            val sessionsRevoked: Boolean,
        )

        if (newPassword != confirmPassword) throw ParamErrorException("两次输入的新密码不一致")
        passwordResetService.resetPassword(token, newPassword)
        refreshCookieService.clear(response)
        val rs = Response(passwordReset = true, sessionsRevoked = true)
        return builder.ok().message("密码已重置，请使用新密码登录").data(rs).build()
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
