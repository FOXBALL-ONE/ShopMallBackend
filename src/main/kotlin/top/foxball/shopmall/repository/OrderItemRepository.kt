package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.OrderItem

interface OrderItemRepository : JpaRepository<OrderItem, Long> {
    fun existsByVariantId(variantId: Long): Boolean

    @Query("select i from OrderItem i where i.order.id = :orderId and i.id in :ids")
    fun findAllByIdForOrder(
        @Param("ids") ids: Collection<Long>,
        @Param("orderId") orderId: Long,
    ): List<OrderItem>

    fun findAllByOrder_IdOrderByVariantIdAsc(orderId: Long): List<OrderItem>

    @Query(
        "select i from OrderItem i where i.order.id in :orderIds " +
            "order by i.order.id, i.variantId, i.id",
    )
    fun findAllByOrderIds(@Param("orderIds") orderIds: Collection<Long>): List<OrderItem>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OrderItem i where i.order.id = :orderId")
    fun deleteAllByOrderId(@Param("orderId") orderId: Long): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from OrderItem i where i.order.id in :orderIds")
    fun deleteAllByOrderIdIn(@Param("orderIds") orderIds: Collection<Long>): Int
}
