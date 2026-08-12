package top.foxball.shopmall.service

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import top.foxball.shopmall.entity.jdbc.CartItem
import top.foxball.shopmall.entity.jdbc.ShoppingCart
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@ActiveProfiles("test")
class ProductAggregateTransactionIntegrationTest @Autowired constructor(
    private val productService: ProductService,
    private val productTypeRepository: ProductTypeRepository,
    private val productVariantRepository: ProductVariantRepository,
    private val shoppingCartRepository: ShoppingCartRepository,
    private val userRepository: UserRepository,
) {
    @Test
    fun `product and variants roll back together when aggregate update fails`() {
        val suffix = UUID.randomUUID().toString().take(8).uppercase()
        val productType = productTypeRepository.saveAndFlush(
            ProductType(code = "TX_$suffix", name = "Transaction Test $suffix"),
        )
        val originalName = "Transaction Dress $suffix"
        val created = productService.create(
            CreateProductCommand(
                productTypeId = requireNotNull(productType.id),
                categoryId = null,
                name = originalName,
                status = Product.Status.INACTIVE,
                highlights = emptyList(),
                materials = emptyList(),
                attributes = emptyList(),
                images = emptyList(),
                fitSense = null,
                description = null,
                designAndExtras = emptyList(),
                careInstructions = emptyList(),
                tagIds = emptySet(),
                variants = listOf(
                    variantInput("TX-$suffix-S", "S"),
                    variantInput("TX-$suffix-M", "M"),
                ),
            ),
        )
        val productId = requireNotNull(created.id)
        val variants = productVariantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId)
        val retained = variants.first()
        val referenced = variants.last()
        val customer = userRepository.saveAndFlush(
            User(
                email = "product-transaction-$suffix@example.com",
                username = "product-transaction-$suffix",
                password = "encoded-password",
            ),
        )
        shoppingCartRepository.saveAndFlush(
            ShoppingCart(customer = customer).apply {
                add(CartItem(variant = referenced, quantity = 1))
            },
        )

        assertFailsWith<ParamErrorException> {
            productService.update(
                productId,
                UpdateProductCommand(
                    categoryId = null,
                    name = "Changed Name $suffix",
                    status = Product.Status.INACTIVE,
                    highlights = listOf("This must roll back"),
                    materials = emptyList(),
                    attributes = emptyList(),
                    images = emptyList(),
                    fitSense = null,
                    description = null,
                    designAndExtras = emptyList(),
                    careInstructions = emptyList(),
                    tagIds = emptySet(),
                    variants = listOf(
                        variantInput(
                            sku = retained.sku,
                            size = retained.size,
                            id = requireNotNull(retained.id),
                        ),
                    ),
                ),
            )
        }

        val reloaded = requireNotNull(productService.getAdmin(productId))
        assertEquals(originalName, reloaded.name)
        assertEquals(emptyList(), reloaded.highlights)
        assertEquals(2, productVariantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId).size)
    }

    private fun variantInput(sku: String, size: String?, id: Long? = null) = ProductVariantInput(
        id = id,
        sku = sku,
        size = size,
        color = "Black",
        price = BigDecimal("49.90"),
        warehouseVolume = 5,
        status = ProductVariant.Status.INACTIVE,
        displayOrder = if (size == "S") 0 else 1,
        attributes = emptyList(),
    )
}
