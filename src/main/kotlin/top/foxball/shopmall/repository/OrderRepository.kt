package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import java.time.Instant

interface OrderRepository : JpaRepository<OrderEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    fun lockById(@Param("id") id: Long): OrderEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.orderNo = :orderNo")
    fun lockByOrderNo(@Param("orderNo") orderNo: String): OrderEntity?

    fun findByOrderNo(orderNo: String): OrderEntity?

    fun findByOrderNoAndCustomerId(orderNo: String, customerId: Long): OrderEntity?

    fun findByCustomerIdOrderByCreatedAtDesc(customerId: Long, pageable: Pageable): Page<OrderEntity>

    @Query(
        "select o from OrderEntity o where " +
            "(:status is null or o.status = :status) and " +
            "(:customerId is null or o.customerId = :customerId) and " +
            "(:orderNo is null or o.orderNo = :orderNo) " +
            "order by o.createdAt desc",
    )
    fun findAllForAdmin(
        @Param("status") status: OrderStatus?,
        @Param("customerId") customerId: Long?,
        @Param("orderNo") orderNo: String?,
        pageable: Pageable,
    ): Page<OrderEntity>

    fun findByPaymentIntentId(paymentIntentId: String): OrderEntity?

    fun findByStripeCheckoutSessionId(sessionId: String): OrderEntity?

    @Query("select o.status from OrderEntity o where o.id = :id")
    fun findStatusById(@Param("id") id: Long): OrderStatus?

    @Query("select o.orderNo from OrderEntity o where o.id = :id")
    fun findOrderNoById(@Param("id") id: Long): String?

    // 按 id 游标推进分页扫描,避免固定 offset 漏扫超出首页的订单
    @Query("select o.id from OrderEntity o where o.status = :status and o.id > :after order by o.id")
    fun findIdsByStatusAfter(@Param("status") status: OrderStatus, @Param("after") after: Long, pageable: Pageable): List<Long>

    @Query(
        "select o.id from OrderEntity o where o.status = :status " +
            "and o.expiresAt is not null and o.expiresAt < :now order by o.expiresAt, o.id",
    )
    fun findExpiredPendingIds(
        @Param("now") now: Instant,
        @Param("status") status: OrderStatus = OrderStatus.PENDING_PAYMENT,
        pageable: Pageable,
    ): List<Long>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update OrderEntity o set o.stripeCheckoutSessionId = :sessionId, o.paymentIntentId = :paymentIntentId " +
            "where o.id = :id and o.status = :status and o.stripeCheckoutSessionId is null",
    )
    fun attachStripeCheckoutSession(
        @Param("id") id: Long,
        @Param("sessionId") sessionId: String,
        @Param("paymentIntentId") paymentIntentId: String?,
        @Param("status") status: OrderStatus = OrderStatus.PENDING_PAYMENT,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update OrderEntity o set o.paymentIntentId = :paymentIntentId " +
            "where o.stripeCheckoutSessionId = :sessionId and o.paymentIntentId is null",
    )
    fun attachPaymentIntentToStripeCheckoutSession(
        @Param("sessionId") sessionId: String,
        @Param("paymentIntentId") paymentIntentId: String,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderEntity o set o.status = :next where o.id = :id and o.status = :expected")
    fun transitionStatus(
        @Param("id") id: Long,
        @Param("expected") expected: OrderStatus,
        @Param("next") next: OrderStatus,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update OrderEntity o set o.status = :next, o.paidAt = :at " +
            "where o.id = :id and o.status = :expected",
    )
    fun markPaid(
        @Param("id") id: Long,
        @Param("expected") expected: OrderStatus,
        @Param("next") next: OrderStatus,
        @Param("at") at: Instant,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update OrderEntity o set o.status = :next, o.cancelledAt = :at, o.cancelReason = :reason " +
            "where o.id = :id and o.status = :expected",
    )
    fun markCancelled(
        @Param("id") id: Long,
        @Param("expected") expected: OrderStatus,
        @Param("next") next: OrderStatus,
        @Param("at") at: Instant,
        @Param("reason") reason: String,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update OrderEntity o set o.status = :next, o.shippedAt = :at " +
            "where o.id = :id and o.status = :expected",
    )
    fun markShipped(
        @Param("id") id: Long,
        @Param("expected") expected: OrderStatus,
        @Param("next") next: OrderStatus,
        @Param("at") at: Instant,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update OrderEntity o set o.status = :next, o.deliveredAt = :at " +
            "where o.id = :id and o.status = :expected",
    )
    fun markDelivered(
        @Param("id") id: Long,
        @Param("expected") expected: OrderStatus,
        @Param("next") next: OrderStatus,
        @Param("at") at: Instant,
    ): Int
}
