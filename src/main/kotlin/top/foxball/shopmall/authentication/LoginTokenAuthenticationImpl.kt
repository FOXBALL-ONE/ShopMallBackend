package top.foxball.shopmall.authentication

import org.springframework.stereotype.Service
import top.foxball.shopmall.config.JwtProperties
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.redis.LoginToken
import top.foxball.shopmall.repository.LoginTokenRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * JWT 登录会话管理：负责令牌签发（委托 [JwtService]）与 Redis 白名单（[LoginToken]）的增删查。
 *
 * 白名单用于主动登出/撤销：即便 JWT 本身未过期，从 Redis 删除对应记录即令其失效。
 * 纯签名/验签逻辑见 [JwtService]（无 Spring、无 Redis 依赖）。
 */
@Service
class LoginTokenAuthenticationImpl(
    private val loginTokenRepository: LoginTokenRepository,
    private val jwtService: JwtService,
    private val jwtProperties: JwtProperties,
) : LoginTokenAuthentication {

    override fun isValid(userId: Long, jti: String, userAgent: String): Boolean {
        // 仅校验白名单与 UA 绑定；JWT 签名/过期由调用方（JwtAuthenticationFilter）先行验签
        val record = loginTokenRepository.findById(jti).orElse(null) ?: return false
        return record.userId == userId && record.userAgent == userAgent
    }

    override fun createToken(userId: Long, userAgent: String): LoginToken {
        val issuedToken = jwtService.issue(userId, jwtProperties.ttlSeconds)
        // 以随机 jti 作为白名单主键，避免在 Redis 中保存可直接重放的原始 JWT。
        val record = LoginToken().apply {
            id = issuedToken.jti
            this.userId = userId
            this.userAgent = userAgent
            expiresInSeconds = ChronoUnit.SECONDS.between(
                LocalDateTime.now(ZoneOffset.UTC),
                issuedToken.expiresAt,
            ).coerceAtLeast(1)
        }
        return loginTokenRepository.save(record).apply { token = issuedToken.token }
    }

    override fun deleteToken(jti: String) {
        loginTokenRepository.deleteById(jti)
    }

    override fun deleteToken(token: LoginToken) {
        loginTokenRepository.delete(token)
    }

    override fun revokeAll(userId: Long) {
        loginTokenRepository.deleteAll(loginTokenRepository.findByUserId(userId))
    }

    override fun findAll(userId: Long): List<LoginToken> =
        loginTokenRepository.findByUserId(userId)

    override fun login(user: User, userAgent: String): LoginTokenAuthentication.LoginResult {
        // 凭据已由 AuthService 校验，此处仅签发令牌并组装登录响应
        val record = createToken(user.id!!, userAgent)
        return LoginTokenAuthentication.LoginResult(
            state = LoginTokenAuthentication.LoginResult.State.SUCCESS,
            response = buildResponse(user, record.token!!),
        )
    }

    private fun buildResponse(
        user: User,
        token: String,
    ): LoginTokenAuthentication.LoginResult.Response =
        LoginTokenAuthentication.LoginResult.Response(
            token = token,
            userId = user.id!!,
            // TODO: frp_token / group / traffic / tunnel 配额待对应模块落地后接入真实数据
            frpToken = "",
            userInfo = LoginTokenAuthentication.LoginResult.Response.UserInfo(
                username = user.username,
                email = user.email,
                limit = LoginTokenAuthentication.LoginResult.Response.Limit(
                    tunnel = null,
                    inbound = DEFAULT_TRAFFIC_LIMIT,
                    outbound = DEFAULT_TRAFFIC_LIMIT,
                ),
                avatar = "",
                traffic = BigDecimal.ZERO,
                registerTime = user.createdAt!!,
                group = LoginTokenAuthentication.LoginResult.Response.Group(
                    id = DEFAULT_GROUP_ID,
                    name = DEFAULT_GROUP_NAME,
                ),
            ),
        )

    private companion object {
        const val DEFAULT_TRAFFIC_LIMIT = 0L
        const val DEFAULT_GROUP_ID = 0L
        const val DEFAULT_GROUP_NAME = "default"
    }
}
