package top.foxball.shopmall.shared

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Component
class ShipmentNoGenerator(
    private val clock: Clock,
) {
    fun next(): String {
        return buildString(32) {
            append('S')
            append(TIMESTAMP_FORMAT.format(clock.instant()))
            repeat(19) { append(RANDOM_ALPHABET[random.nextInt(RANDOM_ALPHABET.length)]) }
        }
    }

    private companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC)
        const val RANDOM_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = SecureRandom()
    }
}
