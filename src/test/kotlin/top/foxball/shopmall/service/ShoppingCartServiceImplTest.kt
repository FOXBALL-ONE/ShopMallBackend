package top.foxball.shopmall.service

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CartItem
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ShoppingCart
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.InsufficientStockException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.ShoppingCartServiceImpl
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingCartServiceImplTest {
    private val cartRepository = mock(ShoppingCartRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val service = ShoppingCartServiceImpl(cartRepository, productRepository, userRepository)

    @Test
    fun `adding a new product creates a cart item with live price totals`() {
        val product = product(id = 11, price = "19.95", stock = 8)
        `when`(productRepository.findByIdAndStatus(11, Product.Status.ACTIVE)).thenReturn(product)
        `when`(cartRepository.findByCustomerIdForUpdate(5)).thenReturn(null)
        `when`(userRepository.findByIdForUpdate(5)).thenReturn(User(id = 5))
        `when`(cartRepository.saveAndFlush(any(ShoppingCart::class.java))).thenAnswer { invocation ->
            invocation.getArgument<ShoppingCart>(0).apply {
                id = 1
                items.single().id = 101
            }
        }

        val view = service.addItem(customerId = 5, productId = 11, quantity = 2)

        assertEquals(2, view.totalQuantity)
        assertEquals(BigDecimal("39.90"), view.subtotal)
        assertEquals(101, view.items.single().id)
        assertEquals("BIKINI", view.items.single().productType)
        assertTrue(view.items.single().purchasable)
    }

    @Test
    fun `adding an existing product increments its quantity`() {
        val product = product(id = 11, price = "10.00", stock = 8)
        val cart = ShoppingCart(id = 1, customer = User(id = 5)).apply {
            add(CartItem(id = 101, product = product, quantity = 2))
        }
        `when`(productRepository.findByIdAndStatus(11, Product.Status.ACTIVE)).thenReturn(product)
        `when`(cartRepository.findByCustomerIdForUpdate(5)).thenReturn(cart)
        `when`(cartRepository.saveAndFlush(cart)).thenReturn(cart)

        val view = service.addItem(customerId = 5, productId = 11, quantity = 3)

        assertEquals(5, view.items.single().quantity)
        assertEquals(BigDecimal("50.00"), view.subtotal)
    }

    @Test
    fun `rejects a quantity larger than current stock`() {
        val product = product(id = 11, price = "10.00", stock = 2)
        `when`(productRepository.findByIdAndStatus(11, Product.Status.ACTIVE)).thenReturn(product)
        `when`(cartRepository.findByCustomerIdForUpdate(5)).thenReturn(ShoppingCart(id = 1, customer = User(id = 5)))

        assertFailsWith<InsufficientStockException> {
            service.addItem(customerId = 5, productId = 11, quantity = 3)
        }
    }

    @Test
    fun `rejects an item owned by another user`() {
        `when`(cartRepository.findByCustomerIdForUpdate(5)).thenReturn(ShoppingCart(id = 1, customer = User(id = 5)))
        `when`(cartRepository.findCustomerIdByItemId(999)).thenReturn(6)

        assertFailsWith<ForbiddenException> {
            service.updateItem(customerId = 5, itemId = 999, quantity = 1)
        }
        verify(cartRepository).findByCustomerIdForUpdate(5)
    }

    @Test
    fun `returns null when the requested cart item does not exist`() {
        `when`(cartRepository.findByCustomerIdForUpdate(5)).thenReturn(ShoppingCart(id = 1, customer = User(id = 5)))
        `when`(cartRepository.findCustomerIdByItemId(999)).thenReturn(null)

        assertNull(service.updateItem(customerId = 5, itemId = 999, quantity = 1))
        verify(cartRepository).findByCustomerIdForUpdate(5)
    }

    @Test
    fun `rejects removal of an item owned by another user`() {
        `when`(cartRepository.findByCustomerIdForUpdate(5)).thenReturn(ShoppingCart(id = 1, customer = User(id = 5)))
        `when`(cartRepository.findCustomerIdByItemId(999)).thenReturn(6)

        assertFailsWith<ForbiddenException> {
            service.removeItem(customerId = 5, itemId = 999)
        }
        verify(cartRepository).findByCustomerIdForUpdate(5)
    }

    private fun product(id: Long, price: String, stock: Int) = BikiniSuit().apply {
        this.id = id
        name = "Ocean Bikini"
        color = "Blue"
        this.price = BigDecimal(price)
        warehouseVolume = stock
        status = Product.Status.ACTIVE
    }
}
