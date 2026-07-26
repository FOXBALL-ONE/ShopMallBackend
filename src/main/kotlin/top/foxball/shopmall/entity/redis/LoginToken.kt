package top.foxball.shopmall.entity.redis

import lombok.AllArgsConstructor
import lombok.Data
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.TimeToLive
import org.springframework.data.redis.core.index.Indexed

/**
 * Redis 会话白名单记录（**已退役**，灰度回退用）。
 *
 * 双 Token 模型下，访问令牌改为无状态验签、刷新令牌改由 [top.foxball.shopmall.shared.RefreshTokenStore]
 * （StringRedisTemplate + Lua）管理，本 `@RedisHash` 白名单不再被任何业务路径引用。
 * 保留一个版本作为灰度回退，确认线上稳定后可连同 [top.foxball.shopmall.repository.LoginTokenRepository]
 * 一并删除。详见 `docs/dual-token-auth-design.md` §八「退役」。
 */
@Deprecated("双 Token 改造后访问白名单已退役，刷新令牌由 RefreshTokenStore 管理；确认无引用后删除")
@RedisHash("login_token")
class LoginToken {
    /** JWT 的随机 jti；只保存会话标识，不保存可直接重放的原始 JWT。 */
    @Id
    var id: String? = null

    /** 会话归属用户，用于注销全部会话和与 JWT 主体进行匹配。 */
    @Indexed
    var userId: Long? = null

    /** 签发时的请求 User-Agent；后续认证请求必须保持一致。 */
    var userAgent: String? = null

    /** 与 JWT 剩余有效期一致的 Redis 过期时间，单位为秒。 */
    @TimeToLive
    var expiresInSeconds: Long? = null

    /** 仅在令牌签发响应中使用，不持久化到 Redis。 */
    @Transient
    var token: String? = null
}
