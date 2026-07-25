package top.foxball.shopmall.shared

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Component
class ShipmentNoGenerator(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    private val random = SecureRandom()
    private val formatter = DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC)

    fun next(): String {
        val sequence = requireNotNull(
            jdbcTemplate.queryForObject("select nextval('shipment_no_seq')", Long::class.java),
        )
        val randomPart = random.nextInt(1_000_000).toString().padStart(6, '0')
        return "S${formatter.format(clock.instant())}${sequence.toString(36).uppercase()}$randomPart"
    }
}
