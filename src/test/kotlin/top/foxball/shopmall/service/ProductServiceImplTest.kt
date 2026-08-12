package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.impl.ProductServiceImpl
import java.math.BigDecimal
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProductServiceImplTest {
    private val productRepository = mock(ProductRepository::class.java)
    private val productTypeRepository = mock(ProductTypeRepository::class.java)
    private val categoryRepository = mock(ProductCategoryRepository::class.java)
    private val variantRepository = mock(ProductVariantRepository::class.java)
    private val tagRepository = mock(TagRepository::class.java)
    private val attributeValidationService = mock(AttributeValidationService::class.java)
    private val optionSignatureService = mock(OptionSignatureService::class.java)
    private val productVariantService = mock(ProductVariantService::class.java)
    private val service = ProductServiceImpl(
        productRepository,
        productTypeRepository,
        categoryRepository,
        variantRepository,
        tagRepository,
        attributeValidationService,
        optionSignatureService,
        productVariantService,
    )

    @Test
    fun `creating a published product requires an active variant`() {
        val type = ProductType(id = 1, code = "DRESS", name = "Dress")
        `when`(productTypeRepository.findById(1)).thenReturn(Optional.of(type))
        `when`(attributeValidationService.validateProduct(1, emptyList())).thenReturn(emptyList())
        `when`(attributeValidationService.validateVariant(1, emptyList())).thenReturn(emptyList())
        `when`(tagRepository.findAllById(emptySet())).thenReturn(emptyList())
        `when`(optionSignatureService.generate("M", "Blue", emptyList())).thenReturn("signature")

        assertFailsWith<ParamErrorException> {
            service.create(createCommand(Product.Status.ACTIVE, ProductVariant.Status.INACTIVE))
        }

        verify(productRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(Product::class.java))
    }

    @Test
    fun `updating a product to published locks it and requires an active variant`() {
        val product = Product(
            id = 10,
            productType = ProductType(id = 1, code = "DRESS", name = "Dress"),
            name = "Draft Dress",
            status = Product.Status.INACTIVE,
        )
        `when`(productRepository.findByIdForUpdate(10)).thenReturn(product)
        `when`(
            variantRepository.countByProduct_IdAndStatus(10, ProductVariant.Status.ACTIVE),
        ).thenReturn(0)

        assertFailsWith<ParamErrorException> {
            service.update(
                10,
                UpdateProductCommand(
                    categoryId = null,
                    name = "Published Dress",
                    status = Product.Status.ACTIVE,
                    highlights = emptyList(),
                    materials = emptyList(),
                    attributes = emptyList(),
                    images = emptyList(),
                    fitSense = null,
                    description = null,
                    designAndExtras = emptyList(),
                    careInstructions = emptyList(),
                    tagIds = emptySet(),
                    variants = createCommand(
                        Product.Status.ACTIVE,
                        ProductVariant.Status.INACTIVE,
                    ).variants,
                ),
            )
        }

        verify(productRepository).findByIdForUpdate(10)
    }

    private fun createCommand(
        productStatus: Product.Status,
        variantStatus: ProductVariant.Status,
    ) = CreateProductCommand(
        productTypeId = 1,
        categoryId = null,
        name = "Summer Dress",
        status = productStatus,
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
            ProductVariantInput(
                sku = "DRESS-BLUE-M",
                size = "M",
                color = "Blue",
                price = BigDecimal("49.90"),
                warehouseVolume = 5,
                status = variantStatus,
                displayOrder = 0,
                attributes = emptyList(),
            ),
        ),
    )
}
