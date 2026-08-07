package top.foxball.shopmall.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Executes the single-key Redis sorted-set sliding-window decision. */
@Service
class ApiRateLimitService(
    private val redis: StringRedisTemplate,
    private val properties: RateLimitProperties,
    private val metrics: RateLimitMetrics,
) {
    fun decide(
        identity: RateLimitIdentityType,
        subject: String,
        limit: Int,
    ): RateLimitDecision {
        if (limit !in RateLimitProperties.MIN_REQUESTS_PER_MINUTE..RateLimitProperties.MAX_REQUESTS_PER_MINUTE) {
            throw RateLimitUnavailableException("Invalid API rate-limit quota")
        }
        val key = when (identity) {
            RateLimitIdentityType.AUTHENTICATED -> {
                val userId = subject.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw RateLimitUnavailableException("Invalid authenticated rate-limit subject")
                "$KEY_PREFIX:user:{$userId}"
            }

            RateLimitIdentityType.ANONYMOUS -> {
                if (subject.isBlank()) throw RateLimitUnavailableException("Invalid anonymous rate-limit subject")
                "$KEY_PREFIX:anonymous:{${anonymousIdentityHash(subject)}}"
            }
        }

        val result = try {
            redis.execute(
                slidingWindowScript,
                listOf(key),
                (properties.windowSeconds * MILLIS_PER_SECOND).toString(),
                limit.toString(),
                UUID.randomUUID().toString(),
            )
        } catch (exception: RuntimeException) {
            throw RateLimitUnavailableException("Unable to evaluate API rate limit", exception)
        } ?: throw RateLimitUnavailableException("Redis returned no API rate-limit decision")

        val parts = result.split(':')
        if (parts.size != 4) {
            throw RateLimitUnavailableException("Redis returned an invalid API rate-limit decision")
        }
        val allowed = when (parts[0]) {
            "1" -> true
            "0" -> false
            else -> throw RateLimitUnavailableException("Redis returned an invalid API rate-limit result")
        }
        val returnedLimit = parts[1].toIntOrNull()
            ?.takeIf { it == limit }
            ?: throw RateLimitUnavailableException("Redis returned an invalid API rate-limit quota")
        val remaining = parts[2].toIntOrNull()
            ?.takeIf { it in 0..returnedLimit }
            ?: throw RateLimitUnavailableException("Redis returned an invalid API rate-limit remaining count")
        val retryAfter = parts[3].toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw RateLimitUnavailableException("Redis returned an invalid API rate-limit retry delay")
        if ((allowed && retryAfter != 0L) || (!allowed && (remaining != 0 || retryAfter < 1))) {
            throw RateLimitUnavailableException("Redis returned an inconsistent API rate-limit decision")
        }

        val decision = RateLimitDecision(allowed, returnedLimit, remaining, retryAfter)
        metrics.request(identity, allowed)
        return decision
    }

    private fun anonymousIdentityHash(clientIp: String): String {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(properties.identityHashSecret.toByteArray(StandardCharsets.UTF_8), HMAC_SHA_256))
        val digest = mac.doFinal(clientIp.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest.copyOf(HMAC_PREFIX_BYTES))
    }

    private companion object {
        const val KEY_PREFIX = "rate-limit:v1"
        const val MILLIS_PER_SECOND = 1_000L
        const val HMAC_SHA_256 = "HmacSHA256"
        const val HMAC_PREFIX_BYTES = 16

        val slidingWindowScript = DefaultRedisScript(
            """
                local nowParts = redis.call('TIME')
                local now = tonumber(nowParts[1]) * 1000 + math.floor(tonumber(nowParts[2]) / 1000)
                local windowMillis = tonumber(ARGV[1])
                local limit = tonumber(ARGV[2])

                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - windowMillis)
                local count = redis.call('ZCARD', KEYS[1])

                if count >= limit then
                    local releaseRank = count - limit
                    local releaseEntry = redis.call('ZRANGE', KEYS[1], releaseRank, releaseRank, 'WITHSCORES')
                    local retryAfter = math.max(1, math.ceil((tonumber(releaseEntry[2]) + windowMillis - now) / 1000))
                    return '0:' .. limit .. ':0:' .. retryAfter
                end

                redis.call('ZADD', KEYS[1], now, ARGV[3])
                redis.call('PEXPIRE', KEYS[1], windowMillis + 1000)
                return '1:' .. limit .. ':' .. (limit - count - 1) .. ':0'
            """.trimIndent(),
            String::class.java,
        )
    }
}
