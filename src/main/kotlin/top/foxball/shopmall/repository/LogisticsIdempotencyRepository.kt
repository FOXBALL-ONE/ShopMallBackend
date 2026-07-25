package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.LogisticsIdempotency

interface LogisticsIdempotencyRepository : JpaRepository<LogisticsIdempotency, Long> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: Long,
        operation: String,
        idempotencyKey: String,
    ): LogisticsIdempotency?
}
