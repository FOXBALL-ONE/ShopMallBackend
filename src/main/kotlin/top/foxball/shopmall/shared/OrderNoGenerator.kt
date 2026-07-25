package top.foxball.shopmall.shared

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Component
class OrderNoGenerator(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) {
    fun next(): String {
        val now = clock.instant().atZone(ZoneOffset.UTC)
        val sequenceKey = "order:seq:${DAY_FORMAT.format(now)}"
        val sequence = redis.opsForValue().increment(sequenceKey)
            ?: error("Redis did not return an order sequence")
        if (sequence == 1L) redis.expire(sequenceKey, Duration.ofHours(25))
        return buildString(26) {
            append(TIMESTAMP_FORMAT.format(now))
            append(sequence.mod(1_000_000).toString().padStart(6, '0'))
            repeat(8) { append(RANDOM_ALPHABET[random.nextInt(RANDOM_ALPHABET.length)]) }
        }
    }

    private companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMddHHmmss")
        const val RANDOM_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = SecureRandom()
    }
}
