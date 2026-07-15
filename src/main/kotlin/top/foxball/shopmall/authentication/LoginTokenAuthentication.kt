package top.foxball.shopmall.authentication

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Service
import top.foxball.shopmall.authentication.annotation.AuthenticationService
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.redis.LoginToken
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 登录会话与令牌生命周期服务：签发/校验/撤销令牌，并组装登录响应。
 *
 * - 令牌采用 JWT（HS256），签名/验签由 [JwtService] 完成；
 * - 会话白名单（撤销查询）落 Redis，见 [LoginToken]。
 */
@Service
@AuthenticationService
interface LoginTokenAuthentication {
    fun isValid(userId: Long, jti: String, userAgent: String): Boolean
    fun createToken(userId: Long, userAgent: String): LoginToken
    fun deleteToken(jti: String)
    fun deleteToken(token: LoginToken)
    fun revokeAll(userId: Long)
    fun findAll(userId: Long): List<LoginToken>

    fun login(user: User, userAgent: String): LoginResult

    data class LoginResult(
        val state: State,
        val response: Response? = null
    ) {
        enum class State {
            SUCCESS,
            GROUP_NOT_ALLOWED,
        }

        data class Response(
            val token: String,
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
}
