package top.foxball.shopmall.repository

import org.springframework.data.repository.CrudRepository
import top.foxball.shopmall.entity.redis.LoginToken

/** Redis 登录会话仓储，按用户查询用于主动撤销该用户的所有会话。 */
interface LoginTokenRepository: CrudRepository<LoginToken, String> {
    fun findByUserId(userId: Long): List<LoginToken>
}
