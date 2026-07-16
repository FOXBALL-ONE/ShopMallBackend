package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverUpValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `accepts a cover up defaulting to one size`() {
        val coverUp = CoverUp(style = CoverUp.CoverUpStyle.KIMONO).apply {
            name = "Crochet Beach Kimono"
            color = "Ivory"
            price = BigDecimal("42.00")
        }

        assertTrue(validator.validate(coverUp).isEmpty())
        assertEquals(CoverUp.Size.ONE_SIZE, coverUp.size)
    }

    @Test
    fun `rejects a non-positive price`() {
        val coverUp = CoverUp().apply {
            name = "Crochet Beach Kimono"
            color = "Ivory"
            price = BigDecimal.ZERO
        }

        val invalidProperties = validator.validate(coverUp).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("price"), invalidProperties)
    }
}
