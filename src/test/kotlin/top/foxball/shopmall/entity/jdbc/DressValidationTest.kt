package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DressValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `accepts a complete dress SKU`() {
        val dress = Dress(
            size = Dress.Size.M,
            length = Dress.Length.MIDI,
            silhouette = Dress.Silhouette.A_LINE,
        ).apply {
            name = "Linen Slip Dress"
            color = "Sand"
            price = BigDecimal("59.00")
        }

        assertTrue(validator.validate(dress).isEmpty())
    }

    @Test
    fun `rejects a missing size and a non-positive price`() {
        val dress = Dress().apply {
            name = "Linen Slip Dress"
            color = "Sand"
            price = BigDecimal.ZERO
        }

        val invalidProperties = validator.validate(dress).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("price", "size"), invalidProperties)
    }

    @Test
    fun `provides the configured S size recommendation`() {
        val recommendation = requireNotNull(Dress.Size.S.recommendation)

        assertEquals(InchRange("34.5", "36.0"), recommendation.bust)
        assertEquals(InchRange("27.0", "28.5"), recommendation.waist)
        assertEquals(InchRange("37.0", "38.5"), recommendation.hip)
    }
}
