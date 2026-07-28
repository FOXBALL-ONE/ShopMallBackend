package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingAddressValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `accepts a complete billing address`() {
        val user = User(
            billingAddress = BillingAddress(
                name = "Alex Doe",
                country = "US",
                city = "Seattle",
                address1 = "1 Market Street",
            ),
        )

        assertTrue(validator.validate(user).isEmpty())
    }

    @Test
    fun `cascades billing address validation`() {
        val user = User(billingAddress = BillingAddress())

        val invalidProperties = validator.validate(user).map { it.propertyPath.toString() }.toSet()
        assertEquals(
            setOf("billingAddress.name", "billingAddress.country", "billingAddress.city", "billingAddress.address1"),
            invalidProperties,
        )
    }
}
