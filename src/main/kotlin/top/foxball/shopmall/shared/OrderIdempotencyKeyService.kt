package top.foxball.shopmall.shared

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.handler.OrderWindowLimitException
import top.foxball.shopmall.repository.OrderRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 服务端签发的下单幂等键生命周期管理。
 *
 * 每个用户同时只持有一个未消费键（Redis `order:key:{customerId}`），作为「持键下单/持键支付」
 * 的授权凭证。键在下单终局（成功或失败）后被消费删除；消费使用条件删除，仅当值仍等于本单键时
 * 才删除，避免误删用户重新申请的新键。DB 中的 [top.foxball.shopmall.entity.jdbc.OrderIdempotency]
 * 行承担键 → 订单的持久绑定，Redis 键丢失不影响已下单的支付授权。
 */
@Component
class OrderIdempotencyKeyService(
    private val redis: StringRedisTemplate,
    private val properties: OrderProperties,
    private val clock: Clock,
    private val orderRepository: OrderRepository,
) {
    /**
     * 为用户签发或返回其当前未消费键；新签发的键带 TTL。
     *
     * 键已被用户持有（幂等签发）时不触发窗口预检；仅在新签发前检查最近订单是否仍在窗口内。
     * 预检是 UX 优化（省一次下单往返），权威窗口判定在下单事务内（用户行锁闸门）。
     */
    @Transactional(readOnly = true)
    fun issue(customerId: Long): IssuedKey {
        val redisKey = redisKey(customerId)
        val existing = redis.opsForValue().get(redisKey)
        if (existing != null) {
            val ttl = redis.getExpire(redisKey)
                ?.takeIf { it > 0 }
                ?.let(Duration::ofSeconds)
                ?: Duration.ofMinutes(properties.idempotencyKeyTtlMinutes)
            return IssuedKey(existing, clock.instant().plus(ttl))
        }

        val latest = orderRepository.findByCustomerIdOrderByCreatedAtDesc(
            customerId,
            org.springframework.data.domain.PageRequest.of(0, 1),
        ).content.firstOrNull()
        if (latest != null) {
            val elapsedSeconds = Duration.between(requireNotNull(latest.createdAt), clock.instant()).seconds
            val windowSeconds = properties.creationWindowMinutes * 60
            if (elapsedSeconds < windowSeconds) {
                throw OrderWindowLimitException(
                    retryAfterSeconds = (windowSeconds - elapsedSeconds).coerceAtLeast(1),
                    message = "距上次下单不足 ${properties.creationWindowMinutes} 分钟，请稍后再试",
                )
            }
        }

        val value = redis.execute(
            issueScript,
            listOf(redisKey),
            UUID.randomUUID().toString(),
            (properties.idempotencyKeyTtlMinutes * 60L).toString(),
        ) ?: error("Redis did not return an issued key")
        val ttl = redis.getExpire(redisKey)
            ?.takeIf { it > 0 }
            ?.let(Duration::ofSeconds)
            ?: Duration.ofMinutes(properties.idempotencyKeyTtlMinutes)
        return IssuedKey(value, clock.instant().plus(ttl))
    }

    /**
     * 校验 [key] 是否为当前用户尚未消费且未过期的签发键。
     *
     * 本方法只读取 Redis，不会在键缺失时隐式签发或续期。
     */
    fun isValidFor(customerId: Long, key: String): Boolean {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return false
        return redis.opsForValue().get(redisKey(customerId))?.contentEquals(normalizedKey) == true
    }

    /** 条件消费：仅当当前值等于 [key] 时删除，返回是否删除了该键。 */
    fun consume(customerId: Long, key: String): Boolean {
        return redis.execute(
            consumeScript,
            listOf(redisKey(customerId)),
            key,
        ) == 1L
    }

    private fun redisKey(customerId: Long): String = "order:key:$customerId"

    private companion object {
        val issueScript = DefaultRedisScript(
            """
                local current = redis.call('GET', KEYS[1])
                if current then return current end
                local inserted = redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2], 'NX')
                if inserted then return ARGV[1] end
                return redis.call('GET', KEYS[1])
            """.trimIndent(),
            String::class.java,
        )

        val consumeScript = DefaultRedisScript(
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

/** 签发结果：幂等键及其过期时间（供前端展示倒计时等提示用，非硬约束）。 */
data class IssuedKey(
    val value: String,
    val expiresAt: java.time.Instant,
)
