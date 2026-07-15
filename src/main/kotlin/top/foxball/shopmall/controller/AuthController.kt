package top.foxball.shopmall.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.service.AuthService
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.shared.Response as ApiResponse

/** 认证相关 HTTP 接口：登录与当前会话的密码修改。 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val builder: ResponseBuilder,
) {

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        http: HttpServletRequest,
    ): ResponseEntity<ApiResponse> {
        // UA 必须与 JwtAuthenticationFilter 校验来源一致（请求头），直接取头，
        // 避免 body 指定的 UA 与后续请求的请求头不一致，导致令牌无法通过白名单校验
        val userAgent = http.getHeader("User-Agent").orEmpty()
        val ip = clientIp(http)
        val result = authService.login(request.identifier, request.password, userAgent, ip)

        // GROUP_NOT_ALLOWED 等非成功分支暂无响应体，回 403
        if (result.state != LoginTokenAuthentication.LoginResult.State.SUCCESS || result.response == null) {
            return builder.forbidden().message("登录被拒绝").build()
        }
        return builder.ok().data(result.response).build()
    }

    /** 登录凭据；`identifier` 可为用户名或邮箱，具体判定由服务层负责。 */
    data class LoginRequest(
        @field:NotBlank val identifier: String,
        @field:NotBlank val password: String,
    )

    @PostMapping("/change-password")
    fun changePassword(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val passwordChanged: Boolean,
            val sessionsRevoked: Boolean,
        )

        authService.changePassword(userId, request.currentPassword, request.newPassword)
        val rs = Response(
            passwordChanged = true,
            sessionsRevoked = true,
        )
        return builder.ok().data(rs).build()
    }

    /** 修改密码请求；新密码长度限制与 Argon2/bcrypt 的输入限制保持一致。 */
    data class ChangePasswordRequest(
        @field:NotBlank
        val currentPassword: String,

        @field:NotBlank
        @field:Size(min = 8, max = 72)
        val newPassword: String,
    )

    private fun clientIp(request: HttpServletRequest): String {
        // 优先取 X-Forwarded-For 首段（经反代/CDN 时为真实客户端 IP，需部署侧确保该头可信），
        // 否则回退到 TCP 直连地址
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.substringBefore(",").trim()
        return request.remoteAddr
    }
}
