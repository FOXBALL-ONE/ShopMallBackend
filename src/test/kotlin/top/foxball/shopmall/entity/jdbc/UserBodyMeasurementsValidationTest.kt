package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验证身体数值与单位必须成对出现，避免数据库中存在无法解释的尺寸。 */
class UserBodyMeasurementsValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `accepts complete body measurements with selected units`() {
        val user = User(
            bust = BigDecimal("86.5"),
            waist = BigDecimal("64.0"),
            hip = BigDecimal("91.0"),
            torso = BigDecimal("51.0"),
            height = BigDecimal("168.0"),
            lengthUnit = LengthUnit.CM,
            birthday = LocalDate.of(1998, 5, 21),
            braSize = "75",
            cupSize = "C",
            weight = BigDecimal("52.4"),
            weightUnit = WeightUnit.KG,
        )

        assertTrue(validator.validate(user).isEmpty())
    }

    @Test
    fun `rejects measurements when their corresponding unit is absent`() {
        val user = User(
            bust = BigDecimal("34"),
            weight = BigDecimal("120"),
        )

        val invalidProperties = validator.validate(user).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("lengthUnitValid", "weightUnitValid"), invalidProperties)
    }

    @Test
    fun `rejects a birthday in the future`() {
        val user = User(birthday = LocalDate.now().plusDays(1))

        val invalidProperties = validator.validate(user).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("birthday"), invalidProperties)
    }
}
