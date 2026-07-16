package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnePieceSuitValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `accepts a complete one piece suit SKU`() {
        val suit = OnePieceSuit(
            size = OnePieceSuit.Size.M,
            supportLevel = OnePieceSuit.SupportLevel.MEDIUM,
            coverage = OnePieceSuit.Coverage.FULL,
            torsoFit = OnePieceSuit.TorsoFit.REGULAR,
        ).apply {
            name = "Sculpting Scoop One Piece"
            color = "Navy"
            price = BigDecimal("98.00")
        }

        assertTrue(validator.validate(suit).isEmpty())
    }

    @Test
    fun `rejects a missing size and a non-positive price`() {
        val suit = OnePieceSuit().apply {
            name = "Sculpting Scoop One Piece"
            color = "Navy"
            price = BigDecimal.ZERO
        }

        val invalidProperties = validator.validate(suit).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("price", "size"), invalidProperties)
    }

    @Test
    fun `provides the configured S size recommendation`() {
        val recommendation = requireNotNull(OnePieceSuit.Size.S.recommendation)

        assertEquals(listOf("32D", "34B", "34C", "36A"), recommendation.braSizes)
        assertEquals(InchRange("34.5", "36.0"), recommendation.bust)
        assertEquals(InchRange("28.0", "31.0"), recommendation.underbust)
        assertEquals(InchRange("27.0", "28.5"), recommendation.waist)
        assertEquals(InchRange("37.0", "38.5"), recommendation.hip)
        assertEquals(InchRange("61.0", "61.5"), recommendation.torso)
    }
}
