package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.ShoppingCart

interface ShoppingCartRepository : JpaRepository<ShoppingCart, Long> {
    /** 一次加载购物车、明细、SKU 与 SPU 基础字段，供只读展示使用。 */
    @EntityGraph(attributePaths = ["items", "items.variant", "items.variant.product", "items.variant.product.productType"])
    fun findDetailedByCustomerId(customerId: Long): ShoppingCart?

    /** 写操作串行锁定同一用户的购物车，防止并发数量更新丢失。 */
    @EntityGraph(attributePaths = ["items", "items.variant", "items.variant.product", "items.variant.product.productType"])
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ShoppingCart c where c.customer.id = :customerId")
    fun findByCustomerIdForUpdate(@Param("customerId") customerId: Long): ShoppingCart?

    /** 查找购物车明细所属用户，用于把跨用户操作返回为 403。 */
    @Query("select c.customer.id from ShoppingCart c join c.items item where item.id = :itemId")
    fun findCustomerIdByItemId(@Param("itemId") itemId: Long): Long?

    fun deleteByCustomerId(customerId: Long)

    fun deleteAllByCustomerIdIn(customerIds: Collection<Long>)

    @Query("select (count(i) > 0) from CartItem i where i.variant.id = :variantId")
    fun existsItemByVariantId(@Param("variantId") variantId: Long): Boolean
}
