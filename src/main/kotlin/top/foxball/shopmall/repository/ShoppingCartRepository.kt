package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.ShoppingCart

interface ShoppingCartRepository : JpaRepository<ShoppingCart, Long> {
    /** 一次加载购物车、明细和商品基础字段，供只读展示使用。 */
    @EntityGraph(attributePaths = ["items", "items.product"])
    fun findDetailedByCustomerId(customerId: Long): ShoppingCart?

    /** 写操作串行锁定同一用户的购物车，防止并发数量更新丢失。 */
    @EntityGraph(attributePaths = ["items", "items.product"])
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ShoppingCart c where c.customer.id = :customerId")
    fun findByCustomerIdForUpdate(@Param("customerId") customerId: Long): ShoppingCart?

    fun deleteByCustomerId(customerId: Long)

    fun deleteAllByCustomerIdIn(customerIds: Collection<Long>)
}
