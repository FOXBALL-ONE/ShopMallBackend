package top.foxball.shopmall.service

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.service.impl.ProductVariantServiceImpl
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProductVariantServiceImplTest {
    private val productRepository = mock(ProductRepository::class.java)
    private val variantRepository = mock(ProductVariantRepository::class.java)
    private val shoppingCartRepository = mock(ShoppingCartRepository::class.java)
    private val orderItemRepository = mock(OrderItemRepository::class.java)
    private val attributeValidationService = mock(AttributeValidationService::class.java)
    private val optionSignatureService = mock(OptionSignatureService::class.java)
    private val service = ProductVariantServiceImpl(
        productRepository,
        variantRepository,
        shoppingCartRepository,
        orderItemRepository,
        attributeValidationService,
        optionSignatureService,
    )

    @Test
    fun `creating a variant locks its product and normalizes the sku and price`() {
        val product = product()
        val input = input(sku = " bikini-blue-l ", status = ProductVariant.Status.ACTIVE)
        `when`(productRepository.findByIdForUpdate(10)).thenReturn(product)
        `when`(attributeValidationService.validateVariant(1, emptyList())).thenReturn(emptyList())
        `when`(optionSignatureService.generate("L", "Blue", emptyList())).thenReturn("signature-l-blue")
        `when`(productRepository.saveAndFlush(product)).thenReturn(product)

        val created = service.create(10, input)!!

        assertEquals("BIKINI-BLUE-L", created.sku)
        assertEquals(BigDecimal("29.90"), created.price)
        assertSame(product, created.product)
        verify(productRepository).findByIdForUpdate(10)
        verify(productRepository).saveAndFlush(product)
    }

    @Test
    fun `normal update cannot deactivate the last active variant of a published product`() {
        val product = product(status = Product.Status.ACTIVE)
        val variant = variant(product, status = ProductVariant.Status.ACTIVE)
        `when`(variantRepository.findProductIdById(20)).thenReturn(10)
        `when`(productRepository.findByIdForUpdate(10)).thenReturn(product)
        `when`(variantRepository.findDetailedById(20)).thenReturn(variant)
        `when`(attributeValidationService.validateVariant(1, emptyList())).thenReturn(emptyList())
        `when`(optionSignatureService.generate("M", "Blue", emptyList())).thenReturn("signature-m-blue")
        `when`(
            variantRepository.countByProduct_IdAndStatus(10, ProductVariant.Status.ACTIVE),
        ).thenReturn(1)

        assertFailsWith<ParamErrorException> {
            service.update(20, input(status = ProductVariant.Status.INACTIVE))
        }

        verify(productRepository).findByIdForUpdate(10)
        verify(variantRepository, never()).saveAndFlush(any(ProductVariant::class.java))
    }

    @Test
    fun `deleting the last active variant is rejected even when an inactive variant remains`() {
        val product = product(status = Product.Status.ACTIVE)
        val active = variant(product, id = 20, status = ProductVariant.Status.ACTIVE)
        val inactive = variant(product, id = 21, status = ProductVariant.Status.INACTIVE)
        `when`(variantRepository.findProductIdById(20)).thenReturn(10)
        `when`(productRepository.findByIdForUpdate(10)).thenReturn(product)
        `when`(variantRepository.findDetailedById(20)).thenReturn(active)
        `when`(variantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(10))
            .thenReturn(listOf(active, inactive))
        `when`(
            variantRepository.countByProduct_IdAndStatus(10, ProductVariant.Status.ACTIVE),
        ).thenReturn(1)

        assertFailsWith<ParamErrorException> { service.delete(20) }

        verify(variantRepository, never()).delete(active)
    }

    private fun product(status: Product.Status = Product.Status.INACTIVE) = Product(
        id = 10,
        productType = ProductType(id = 1, code = "BIKINI", name = "Bikini"),
        name = "Ocean Bikini",
        status = status,
    )

    private fun variant(
        product: Product,
        id: Long = 20,
        status: ProductVariant.Status,
    ) = ProductVariant(
        id = id,
        product = product,
        sku = "BIKINI-BLUE-M-$id",
        size = "M",
        color = "Blue",
        price = BigDecimal("29.90"),
        warehouseVolume = 5,
        status = status,
        optionSignature = "signature-$id",
    )

    private fun input(
        sku: String = "BIKINI-BLUE-M-20",
        status: ProductVariant.Status,
    ) = ProductVariantInput(
        sku = sku,
        size = if (sku.contains("-L", ignoreCase = true)) " l " else "M",
        color = "Blue",
        price = BigDecimal("29.9"),
        warehouseVolume = 5,
        status = status,
        displayOrder = 0,
        attributes = emptyList(),
    )
}
