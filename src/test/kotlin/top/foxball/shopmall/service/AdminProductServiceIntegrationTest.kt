package top.foxball.shopmall.service

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.CartItem
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ShoppingCart
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.CustomerReviewRepository
import top.foxball.shopmall.repository.DressRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.foxball.shopmall.handler.ParamErrorException

@SpringBootTest
@ActiveProfiles("test")
class AdminProductServiceIntegrationTest {
    @Autowired
    private lateinit var dressRepository: DressRepository

    @Autowired
    private lateinit var adminProductService: AdminProductService

    @Autowired
    private lateinit var customerReviewRepository: CustomerReviewRepository

    @Autowired
    private lateinit var shoppingCartRepository: ShoppingCartRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    @Transactional
    fun `unified query filters inherited products and stock adjustment is applied`() {
        val dress = dressRepository.saveAndFlush(
            Dress(size = Dress.Size.M).apply {
                name = "Integration Summer Dress"
                color = "Green"
                price = BigDecimal("59.90")
                warehouseVolume = 5
                status = Product.Status.ACTIVE
            },
        )

        val result = adminProductService.list(
            productType = AdminProductService.ProductType.DRESS,
            status = Product.Status.ACTIVE,
            keyword = "summer",
            lowStock = true,
            lowStockThreshold = 5,
            sortBy = AdminProductService.SortBy.STOCK,
            ascending = true,
            page = 0,
            size = 20,
        )

        assertEquals(listOf(dress.id), result.content.map { it.id })
        assertEquals(3, adminProductService.adjustStock(requireNotNull(dress.id), -2))
    }

    @Test
    @Transactional
    fun `product must be logically deleted before it can be physically deleted`() {
        val dress = dressRepository.saveAndFlush(
            Dress(size = Dress.Size.M).apply {
                name = "Permanent deletion dress"
                color = "Black"
                price = BigDecimal("69.90")
                status = Product.Status.INACTIVE
            },
        )
        val id = requireNotNull(dress.id)
        val customer = userRepository.saveAndFlush(
            User(
                email = "product-delete@example.test",
                username = "product-delete-customer",
                password = "test-password-hash",
            ),
        )
        val cart = ShoppingCart(customer = customer).apply {
            add(CartItem(product = dress, quantity = 1))
        }
        shoppingCartRepository.saveAndFlush(cart)
        val review = customerReviewRepository.saveAndFlush(
            CustomerReview(
                product = dress,
                customerId = requireNotNull(customer.id),
                rating = 5,
                content = "Review removed with permanently deleted product",
            ),
        )

        assertFailsWith<ParamErrorException> { adminProductService.permanentlyDelete(listOf(id)) }
        assertTrue(dressRepository.existsById(id))

        assertEquals(1, adminProductService.softDelete(listOf(id)))
        assertEquals(Product.Status.DELETED, dressRepository.findById(id).orElseThrow().status)
        assertEquals(1, adminProductService.permanentlyDelete(listOf(id)))

        entityManager.clear()
        assertFalse(dressRepository.existsById(id))
        assertFalse(customerReviewRepository.existsById(requireNotNull(review.id)))
        assertTrue(
            shoppingCartRepository.findDetailedByCustomerId(requireNotNull(customer.id))?.items.orEmpty().isEmpty(),
        )
    }
}
