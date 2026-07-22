package top.foxball.shopmall.repository

import org.springframework.data.repository.CrudRepository
import top.foxball.shopmall.entity.redis.LoginToken

/** Redis 登录会话仓储（**已退役**，灰度回退用）。按用户查询用于主动撤销该用户的所有会话。
 *  双 Token 改造后改由 [top.foxball.shopmall.shared.RefreshTokenStore.revokeAll] 承担，确认无引用后删除。 */
@Deprecated("双 Token 改造后访问白名单已退役，刷新令牌由 RefreshTokenStore 管理；确认无引用后删除")
interface LoginTokenRepository: CrudRepository<LoginToken, String> {
    fun findByUserId(userId: Long): List<LoginToken>
}
