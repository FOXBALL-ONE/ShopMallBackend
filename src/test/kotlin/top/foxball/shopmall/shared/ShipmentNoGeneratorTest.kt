package top.foxball.shopmall.shared

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShipmentNoGeneratorTest {
    private val generator = ShipmentNoGenerator(
        Clock.fixed(Instant.parse("2026-08-19T12:55:44Z"), ZoneOffset.UTC),
    )

    @Test
    fun `generates fixed-length shipment number without database sequence`() {
        val shipmentNo = generator.next()

        assertEquals(32, shipmentNo.length)
        assertTrue(shipmentNo.matches(Regex("S260819125544[0-9A-Z]{19}")))
    }

    @Test
    fun `generates unique shipment numbers within the same second`() {
        val shipmentNos = List(1_000) { generator.next() }

        assertEquals(shipmentNos.size, shipmentNos.toSet().size)
    }
}
