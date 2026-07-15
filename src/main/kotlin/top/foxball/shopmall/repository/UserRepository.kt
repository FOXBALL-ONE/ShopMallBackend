package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.User

/** 用户持久化查询；带 `EntityGraph` 的方法用于一次加载配送地址集合。 */
interface UserRepository : JpaRepository<User, Long> {
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean
    fun findByUsername(username: String): User?

    fun findByEmail(email: String): User?

    @EntityGraph(attributePaths = ["deliveryAddress"])
    fun findWithDeliveryAddressById(id: Long): User?

    @EntityGraph(attributePaths = ["deliveryAddress"])
    fun findAllWithDeliveryAddressByIdIn(ids: Collection<Long>): List<User>
}
