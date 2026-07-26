package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.OrderIdempotency

interface OrderIdempotencyRepository : JpaRepository<OrderIdempotency, Long> {
    fun findByCustomerIdAndIdempotencyKey(
        customerId: Long,
        idempotencyKey: String,
    ): OrderIdempotency?
}
