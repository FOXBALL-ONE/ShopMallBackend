package top.foxball.shopmall.logistics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ManualCarrierTest {
    private val carrier = ManualCarrier()

    @Test
    fun `normalizes tracking number and exposes no remote capabilities`() {
        assertEquals("TRACK-001", carrier.normalizeTrackingNo(" track-001 "))
        assertFalse(carrier.capabilities.remoteLabel)
        assertFalse(carrier.capabilities.webhook)
        assertFalse(carrier.capabilities.polling)
        assertNull(carrier.trackingUrl("TRACK-001"))
    }
}
