package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.OrderIdempotency

interface OrderIdempotencyRepository : JpaRepository<OrderIdempotency, Long> {
    fun findByCustomerIdAndIdempotencyKey(
        customerId: Long,
        idempotencyKey: String,
    ): OrderIdempotency?

    /** 供支付链接校验：查询订单对应的幂等键绑定。 */
    fun findByCustomerIdAndOrderNo(customerId: Long, orderNo: String): OrderIdempotency?
}
