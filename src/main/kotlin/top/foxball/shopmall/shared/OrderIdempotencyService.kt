package top.foxball.shopmall.shared

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import top.foxball.shopmall.config.OrderProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

@Component
class OrderIdempotencyService(
    private val redis: StringRedisTemplate,
    private val properties: OrderProperties,
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
