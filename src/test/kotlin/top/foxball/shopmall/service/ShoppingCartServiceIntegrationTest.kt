package top.foxball.shopmall.service

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.BikiniSuitRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShoppingCartServiceIntegrationTest @Autowired constructor(
    private val shoppingCartService: ShoppingCartService,
    private val userRepository: UserRepository,
    private val bikiniSuitRepository: BikiniSuitRepository,
    private val shoppingCartRepository: ShoppingCartRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val entityManager: EntityManager,
) {
    @Test
    fun `persists cart items with timestamps and batch loaded primary images`() {
        val suffix = System.nanoTime()
        val customer = userRepository.saveAndFlush(
            User(
                email = "cart-$suffix@example.com",
                username = "cart-$suffix",
                password = "encoded-password",
            ),
        )
        val product = bikiniSuitRepository.saveAndFlush(
            BikiniSuit().apply {
                name = "Ocean Bikini"
                color = "Blue"
                price = BigDecimal("29.99")
                warehouseVolume = 5
                images.add("https://example.com/primary.jpg")
                images.add("https://example.com/secondary.jpg")
            },
        )
        val customerId = requireNotNull(customer.id)
        val productId = requireNotNull(product.id)

        val added = shoppingCartService.addItem(customerId, productId, 1)
        val item = added.items.single()

        assertEquals("https://example.com/primary.jpg", item.primaryImage)
        assertNotNull(added.updatedAt)
        assertNotNull(item.createdAt)
        assertNotNull(item.updatedAt)

        val updated = shoppingCartService.updateItem(customerId, item.id, 2)
        assertNotNull(updated)
        assertEquals(2, updated.items.single().quantity)
        assertNotNull(updated.updatedAt)
        assertNotNull(updated.items.single().updatedAt)

        val persistedCart = shoppingCartRepository.findDetailedByCustomerId(customerId)
        assertNotNull(persistedCart)
        assertEquals(customerId, persistedCart.customer?.id)
        assertEquals(2, persistedCart.items.single().quantity)
        assertTrue(persistedCart.items.single().product?.id == productId)
    }

    @Test
    fun `foreign keys protect carts and deleting a user cascades their cart`() {
        val suffix = System.nanoTime()
        val customer = userRepository.saveAndFlush(
            User(
                email = "cart-fk-$suffix@example.com",
                username = "cart-fk-$suffix",
                password = "encoded-password",
            ),
        )
        val product = bikiniSuitRepository.saveAndFlush(
            BikiniSuit().apply {
                name = "Ocean Bikini"
                color = "Blue"
                price = BigDecimal("29.99")
                warehouseVolume = 5
            },
        )
        val customerId = requireNotNull(customer.id)
        val productId = requireNotNull(product.id)
        shoppingCartService.addItem(customerId, productId, 1)
        val cartId = jdbcTemplate.queryForObject(
            "select id from shopping_carts where customer_id = ?",
            Long::class.java,
            customerId,
        )!!

        assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "insert into shopping_carts (customer_id, created_at, updated_at, version) " +
                    "values (?, current_timestamp, current_timestamp, 0)",
                -1L,
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "insert into shopping_cart_items (cart_id, product_id, quantity, created_at, updated_at) " +
                    "values (?, ?, 1, current_timestamp, current_timestamp)",
                cartId,
                -1L,
            )
        }

        userRepository.deleteById(customerId)
        userRepository.flush()
        entityManager.clear()

        assertNull(shoppingCartRepository.findDetailedByCustomerId(customerId))
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "select count(*) from shopping_carts where customer_id = ?",
                Int::class.java,
                customerId,
            ),
        )
    }
}
