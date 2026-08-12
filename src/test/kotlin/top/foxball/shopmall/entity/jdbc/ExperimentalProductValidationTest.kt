package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExperimentalProductValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `rejects invalid material percentages and primary image configuration`() {
        val product = Product(
            name = "Swimsuit",
            materials = mutableListOf(
                MaterialComponent(name = "Nylon", percentage = BigDecimal("80.00")),
            ),
            images = mutableListOf(
                ProductImage(url = "https://example.com/front.jpg"),
                ProductImage(url = "https://example.com/back.jpg"),
            ),
        )

        val violatedProperties = validator.validate(product).map { it.propertyPath.toString() }.toSet()

        assertTrue("materialPercentageValid" in violatedProperties)
        assertTrue("primaryImageConfigurationValid" in violatedProperties)
    }

    @Test
    fun `rejects an invalid sellable variant`() {
        val variant = ProductVariant(
            sku = "",
            size = "",
            color = "",
            price = BigDecimal.ZERO,
            warehouseVolume = -1,
        )

        val violatedProperties = validator.validate(variant).map { it.propertyPath.toString() }.toSet()

        assertTrue("sku" in violatedProperties)
        assertTrue("color" in violatedProperties)
        assertTrue("price" in violatedProperties)
        assertTrue("warehouseVolume" in violatedProperties)
        assertTrue("product" in violatedProperties)
    }

    @Test
    fun `moving a variant keeps the bidirectional association consistent`() {
        val firstProduct = Product(name = "First")
        val secondProduct = Product(name = "Second")
        val variant = ProductVariant(
            sku = "SKU-001",
            size = "M",
            color = "Black",
            price = BigDecimal("29.99"),
        )

        firstProduct.addVariant(variant)
        secondProduct.addVariant(variant)

        assertFalse(variant in firstProduct.variants)
        assertTrue(variant in secondProduct.variants)
        assertSame(secondProduct, variant.product)
    }
}
