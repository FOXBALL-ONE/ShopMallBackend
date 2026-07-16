package top.foxball.shopmall.entity.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BikiniSuitSizeRecommendationTest {
    @Test
    fun `provides the configured S size recommendation`() {
        val recommendation = assertNotNull(BikiniSuit.Size.S.recommendation)

        assertEquals(listOf("32D", "34B", "34C", "36A"), recommendation.braSizes)
        assertEquals(InchRange("34.5", "36.0"), recommendation.bust)
        assertEquals(InchRange("28.0", "31.0"), recommendation.underbust)
        assertEquals(InchRange("27.0", "28.5"), recommendation.waist)
        assertEquals(InchRange("37.0", "38.5"), recommendation.hip)
        assertEquals(InchRange("61.0", "61.5"), recommendation.torso)
    }
}
