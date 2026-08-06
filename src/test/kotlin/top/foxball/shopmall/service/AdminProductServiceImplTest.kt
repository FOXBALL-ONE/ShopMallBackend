package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.impl.AdminProductServiceImpl
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminProductServiceImplTest {
    @Test
    fun `stock increase uses guarded repository update and returns refreshed value`() {
        val repository = mock(ProductRepository::class.java)
        val service = AdminProductServiceImpl(repository)
        val before = Dress(size = Dress.Size.M).apply {
            id = 8L
            warehouseVolume = 4
        }
        val after = Dress(size = Dress.Size.M).apply {
            id = 8L
            warehouseVolume = 7
        }
        `when`(repository.findById(8L)).thenReturn(Optional.of(before), Optional.of(after))
        `when`(repository.increaseAdminStock(8L, 3, Int.MAX_VALUE - 3)).thenReturn(1)

        assertEquals(7, service.adjustStock(8L, 3))
        verify(repository).increaseAdminStock(8L, 3, Int.MAX_VALUE - 3)
    }

    @Test
    fun `stock decrease rejects an adjustment that would make inventory negative`() {
        val repository = mock(ProductRepository::class.java)
        val service = AdminProductServiceImpl(repository)
        val product = Dress(size = Dress.Size.M).apply {
            id = 8L
            warehouseVolume = 2
        }
        `when`(repository.findById(8L)).thenReturn(Optional.of(product))
        `when`(repository.decreaseAdminStock(8L, 3)).thenReturn(0)

        assertFailsWith<ParamErrorException> { service.adjustStock(8L, -3) }
    }

    @Test
    fun `deleted product cannot be reactivated through status endpoint`() {
        val repository = mock(ProductRepository::class.java)
        val service = AdminProductServiceImpl(repository)
        val product = Dress(size = Dress.Size.M).apply {
            id = 8L
            status = Product.Status.DELETED
        }
        `when`(repository.findById(8L)).thenReturn(Optional.of(product))

        assertFailsWith<ParamErrorException> { service.updateStatus(8L, Product.Status.ACTIVE) }
        verify(repository, never()).updateAdminStatus(8L, Product.Status.ACTIVE)
    }

    @Test
    fun `permanent deletion rejects products that are not logically deleted`() {
        val repository = mock(ProductRepository::class.java)
        val service = AdminProductServiceImpl(repository)
        val product = Dress(size = Dress.Size.M).apply {
            id = 8L
            status = Product.Status.INACTIVE
        }
        `when`(repository.findAllByIdForUpdate(listOf(8L))).thenReturn(listOf(product))

        assertFailsWith<ParamErrorException> { service.permanentlyDelete(listOf(8L)) }
        verify(repository, never()).deleteAdminCartItemsForProducts(listOf(8L))
        verify(repository, never()).deleteAdminReviewsForProducts(listOf(8L))
        verify(repository, never()).deleteAll(listOf(product))
        verify(repository, never()).flush()
    }

    @Test
    fun `permanent deletion removes a locked logically deleted product`() {
        val repository = mock(ProductRepository::class.java)
        val service = AdminProductServiceImpl(repository)
        val product = Dress(size = Dress.Size.M).apply {
            id = 8L
            status = Product.Status.DELETED
        }
        `when`(repository.findAllByIdForUpdate(listOf(8L))).thenReturn(listOf(product))

        assertEquals(1, service.permanentlyDelete(listOf(8L, 8L)))
        verify(repository).deleteAdminCartItemsForProducts(listOf(8L))
        verify(repository).deleteAdminReviewsForProducts(listOf(8L))
        verify(repository).deleteAll(listOf(product))
        verify(repository, times(1)).flush()
    }
}
