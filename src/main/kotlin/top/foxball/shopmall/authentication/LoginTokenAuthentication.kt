package top.foxball.shopmall.authentication

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Service
import top.foxball.shopmall.authentication.annotation.AuthenticationService
import top.foxball.shopmall.entity.jdbc.User
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 登录会话与令牌生命周期服务：双 Token（Access + Refresh）模型的签发、轮换、撤销与登录响应组装。
 *
 * - 访问令牌（access，HS256，typ=access，短有效期）：签名/验签由 [JwtService] 完成，无状态、不查 Redis；
 *   令牌内嵌 `role` claim，过滤器据此映射为 Spring Security authority。
 * - 刷新令牌（refresh，typ=refresh，长有效期）：轮换状态落 Redis（[RefreshTokenStore]），支持原子轮换 +
 *   grace 重试 + 复用检测；走 HttpOnly Cookie（见 [RefreshCookieService]），不进响应体。
 * - 登出 / 改密撤销：[revokeAll] 删除该用户全部 refresh 记录；access 在 ≤ [top.foxball.shopmall.config.JwtProperties.Access.ttlSeconds] 内自然过期。
 *
 * 设计详见 `docs/dual-token-auth-design.md`。
 */
@Service
@AuthenticationService
interface LoginTokenAuthentication {

    /** 登录：签发 access + refresh（refresh 经 store 落 Redis），返回 access 与 refresh 两段。 */
    fun login(user: User, userAgent: String): LoginResult

    /**
     * 续期：用 refresh JWT 原子轮换，签发新 access + 新 refresh（滚动）。
     *
     * [refreshJwt] 来自 HttpOnly Cookie；[userAgent] 取请求头，须与签发时一致（UA 绑定）。
     * 失败（无效/过期/复用/用户禁用）抛 [top.foxball.shopmall.handler.TokenInvalidException] 或
     * [top.foxball.shopmall.handler.UserDisabledException]。
     */
    fun refresh(refreshJwt: String, userAgent: String): RefreshResult

    /** 登出：撤销当前 refresh 记录（按 [refreshJwt] 的 jti）。无记录或无效令牌幂等返回。 */
    fun logout(refreshJwt: String?)

    /** 撤销某用户的全部刷新令牌（改密 / 禁用场景）；access 在过期前仍可用。 */
    fun revokeAll(userId: Long)

    data class LoginResult(
        val state: State,
        val response: Response? = null,
        /** 内部：登录签发的 refresh JWT，供控制器写 HttpOnly Cookie，不进响应体。 */
        val refreshJwt: String? = null,
    ) {
        enum class State {
            SUCCESS,
            GROUP_NOT_ALLOWED,
        }

        data class Response(
            @get:JsonProperty("access_token")
            val accessToken: String,
            @get:JsonProperty("expires_in")
            val expiresIn: Long,
            @get:JsonProperty("user_id")
            val userId: Long,
            @get:JsonProperty("frp_token")
            val frpToken: String,
            @get:JsonProperty("user_info")
            val userInfo: UserInfo,
        ) {
            data class Limit(
                val tunnel: Long?,
                val inbound: Long,
                val outbound: Long
            )

            data class Group(
                val id: Long,
                val name: String,
            )

            data class UserInfo(
                val username: String,
                val email: String,
                val limit: Limit,
                val avatar: String,
                val traffic: BigDecimal,
                @get:JsonProperty("register_time")
                val registerTime: LocalDateTime,
                val group: Group,
            )
        }
    }

    /** 续期结果：新 access（进响应体）+ 新 refresh（写 cookie，滚动）。 */
    data class RefreshResult(
        @get:JsonProperty("access_token")
        val accessToken: String,
        @get:JsonProperty("expires_in")
        val expiresIn: Long,
        /** 内部：滚动后的新 refresh JWT，供控制器写 Cookie，不进响应体。 */
        val refreshJwt: String,
    )
}
