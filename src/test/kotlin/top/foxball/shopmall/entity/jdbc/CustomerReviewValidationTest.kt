package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomerReviewValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `accepts a review linked to a product`() {
        val review = CustomerReview(
            product = BikiniSuit(),
            customerId = 101,
            rating = 5,
            content = "Comfortable and true to size.",
        )

        assertTrue(validator.validate(review).isEmpty())
    }

    @Test
    fun `rejects a review without a product or with an invalid rating`() {
        val review = CustomerReview(
            customerId = 101,
            rating = 6,
            content = "Comfortable and true to size.",
        )

        val invalidProperties = validator.validate(review).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("product", "rating"), invalidProperties)
    }
}
