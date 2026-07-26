package top.foxball.shopmall.shared

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.OrderIdempotency
import top.foxball.shopmall.handler.IdempotencyConflictException
import top.foxball.shopmall.repository.OrderIdempotencyRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

@Component
class OrderIdempotencyService(
    private val redis: StringRedisTemplate,
    private val properties: OrderProperties,
    private val repository: OrderIdempotencyRepository,
) {
    sealed interface Acquisition {
        data object Acquired : Acquisition
        data object Pending : Acquisition
        data class Completed(val orderNo: String) : Acquisition
        data class Rejected(val message: String) : Acquisition
    }

    fun acquire(customerId: Long, clientKey: String): Acquisition {
        val value = redis.execute(
            acquireScript,
            listOf(redisKey(customerId, clientKey)),
            properties.idempotencyTtlSeconds.toString(),
        ) ?: PENDING
        return when {
            value == ACQUIRED -> Acquisition.Acquired
            value == PENDING -> Acquisition.Pending
            value.startsWith(COMPLETED_PREFIX) -> Acquisition.Completed(value.removePrefix(COMPLETED_PREFIX))
            value.startsWith(REJECTED_PREFIX) -> Acquisition.Rejected(value.removePrefix(REJECTED_PREFIX))
            else -> Acquisition.Pending
        }
    }

    fun complete(customerId: Long, clientKey: String, orderNo: String) {
        redis.opsForValue().set(
            redisKey(customerId, clientKey),
            "$COMPLETED_PREFIX$orderNo",
            Duration.ofSeconds(properties.idempotencyTtlSeconds),
        )
    }

    fun reject(customerId: Long, clientKey: String, message: String) {
        redis.opsForValue().set(
            redisKey(customerId, clientKey),
            "$REJECTED_PREFIX${message.take(300)}",
            Duration.ofSeconds(properties.idempotencyTtlSeconds),
        )
    }

    fun release(customerId: Long, clientKey: String) {
        redis.execute(releaseScript, listOf(redisKey(customerId, clientKey)), PENDING)
    }

    // DB 幂等兜底：在 Redis afterCommit 回写失败、TTL 过期后，DB 记录仍是判断「同一 (customerId, idempotencyKey) 是否已下单」的最终事实来源。
    // 该方法在 createOrder 事务外/事务内均可调用（纯读取），用于 Acquired 分支开头回放，避免 Redis 误判首次时重复下单。

    // 回放：查 DB 幂等行。命中且 requestHash 匹配 → 返回已有 orderNo；命中但 hash 不同 → 409；不存在 → null。
    fun replayOrderNo(
        customerId: Long,
        idempotencyKey: String,
        requestHash: String,
    ): String? {
        val existing = repository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
            ?: return null
        if (existing.requestHash != requestHash) {
            throw IdempotencyConflictException()
        }
        return existing.orderNo
    }

    // 写入 DB 幂等行。必须在下单事务内调用，与订单保存原子提交。
    // 使用 saveAndFlush 触发立即 flush，使 uk_order_idempotency 冲突以 DataIntegrityViolationException 形式在调用栈内抛出，
    // 便于调用方在 createOrder 内捕获后重读 replayOrderNo 回放或判定冲突（参考 LogisticsIdempotencyService.record + flush 的模式）。
    // 并发撞 uk_order_idempotency 时抛 DataIntegrityViolationException（由调用方处理：重读 replayOrderNo 回放或判定冲突）。
    fun recordOrderNo(
        customerId: Long,
        idempotencyKey: String,
        requestHash: String,
        orderNo: String,
    ) {
        repository.saveAndFlush(
            OrderIdempotency(
                customerId = customerId,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                orderNo = orderNo,
            ),
        )
    }

    private fun redisKey(customerId: Long, clientKey: String): String =
        "order:idem:$customerId:${hash(clientKey.trim())}"

    private fun hash(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val ACQUIRED = "ACQUIRED"
        const val PENDING = "PENDING"
        const val COMPLETED_PREFIX = "COMPLETED:"
        const val REJECTED_PREFIX = "REJECTED:"

        val acquireScript = DefaultRedisScript(
            """
                local current = redis.call('GET', KEYS[1])
                if current then return current end
                local inserted = redis.call('SET', KEYS[1], 'PENDING', 'EX', ARGV[1], 'NX')
                if inserted then return 'ACQUIRED' end
                return redis.call('GET', KEYS[1])
            """.trimIndent(),
            String::class.java,
        )

        val releaseScript = DefaultRedisScript(
            """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
