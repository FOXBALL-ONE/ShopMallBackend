package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status

/** 用户持久化查询；带 `EntityGraph` 的方法用于一次加载配送地址集合。 */
interface UserRepository : JpaRepository<User, Long> {
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean
    fun existsByUsernameAndIdNot(username: String, id: Long): Boolean
    fun existsByEmailAndIdNot(email: String, id: Long): Boolean
    fun findByUsername(username: String): User?

    fun findByEmail(email: String): User?

    @Query(
        "select u from User u where " +
            "(:keyword is null or lower(u.username) like lower(concat('%', :keyword, '%')) or " +
            "lower(u.email) like lower(concat('%', :keyword, '%')) or " +
            "lower(u.firstName) like lower(concat('%', :keyword, '%')) or " +
            "lower(u.lastName) like lower(concat('%', :keyword, '%'))) and " +
            "(:role is null or u.role = :role) and " +
            "(:status is null or u.status = :status) and " +
            "(:enabled is null or u.enabled = :enabled) " +
            "order by u.createdAt desc, u.id desc",
    )
    fun findAllForAdmin(
        @Param("keyword") keyword: String?,
        @Param("role") role: Role?,
        @Param("status") status: Status?,
        @Param("enabled") enabled: Boolean?,
        pageable: Pageable,
    ): Page<User>

    @EntityGraph(attributePaths = ["deliveryAddress"])
    fun findWithDeliveryAddressById(id: Long): User?

    @EntityGraph(attributePaths = ["deliveryAddress"])
    fun findAllWithDeliveryAddressByIdIn(ids: Collection<Long>): List<User>

    /** 串行化需要依赖用户记录创建唯一子资源的操作，例如首次创建购物车。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): User?

    /** 按稳定顺序锁定用户，供批量删除及其他多用户写操作避免死锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id in :ids order by u.id")
    fun findAllByIdInForUpdate(@Param("ids") ids: Collection<Long>): List<User>
}
