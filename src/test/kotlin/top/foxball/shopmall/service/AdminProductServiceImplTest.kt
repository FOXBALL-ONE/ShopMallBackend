package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.service.impl.AdminProductServiceImpl
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AdminProductServiceImplTest {
    private val productRepository = mock(ProductRepository::class.java)
    private val variantRepository = mock(ProductVariantRepository::class.java)
    private val service = AdminProductServiceImpl(productRepository, variantRepository)

    @Test
    fun `publishing a product locks it and requires an active variant`() {
        val product = Product(
            id = 10,
            productType = ProductType(id = 1, code = "DRESS", name = "Dress"),
            name = "Summer Dress",
            status = Product.Status.INACTIVE,
        )
        `when`(productRepository.findByIdForUpdate(10)).thenReturn(product)
        `when`(
            variantRepository.countByProduct_IdAndStatus(10, ProductVariant.Status.ACTIVE),
        ).thenReturn(0)

        assertFailsWith<ParamErrorException> {
            service.updateStatus(10, Product.Status.ACTIVE)
        }

        verify(productRepository).findByIdForUpdate(10)
        verify(productRepository, never()).saveAndFlush(product)
    }
}
