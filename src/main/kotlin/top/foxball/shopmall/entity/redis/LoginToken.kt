package top.foxball.shopmall.entity.redis

import lombok.AllArgsConstructor
import lombok.Data
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.TimeToLive
import org.springframework.data.redis.core.index.Indexed

/** Redis 会话白名单记录；与 JWT 验签共同决定请求是否仍处于有效登录状态。 */
@RedisHash("login_token")
@Data
@AllArgsConstructor
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
