package top.foxball.shopmall.repository

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import top.foxball.shopmall.entity.jdbc.Dress
import java.math.BigDecimal
import kotlin.test.assertEquals

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderPersistencePostgresIntegrationTest @Autowired constructor(
    private val productRepository: ProductRepository,
    private val webhookEventRepository: StripeWebhookEventRepository,
) {
    @Test
    fun `stock and sales updates target the joined product root atomically`() {
        val productId = requireNotNull(
            productRepository.saveAndFlush(
                Dress(size = Dress.Size.M).apply {
                    name = "Postgres stock test"
                    color = "Blue"
                    price = BigDecimal("29.99")
                    warehouseVolume = 5
                },
            ).id,
        )

        assertEquals(1, productRepository.decrementStock(productId, 2))
        assertEquals(0, productRepository.decrementStock(productId, 4))
        assertEquals(3, productRepository.findById(productId).orElseThrow().warehouseVolume)

        assertEquals(1, productRepository.incrementSales(productId, 2))
        assertEquals(2, productRepository.findById(productId).orElseThrow().salesVolume)
        assertEquals(1, productRepository.restock(productId, 2))
        assertEquals(5, productRepository.findById(productId).orElseThrow().warehouseVolume)
        assertEquals(1, productRepository.decrementSales(productId, 2))
        assertEquals(0, productRepository.findById(productId).orElseThrow().salesVolume)
    }

    @Test
    fun `stripe webhook event claim is database idempotent`() {
        assertEquals(1, webhookEventRepository.claim("evt_pg_1", "payment_intent.succeeded"))
        assertEquals(0, webhookEventRepository.claim("evt_pg_1", "payment_intent.succeeded"))
    }

    companion object {
        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.flyway.enabled") { false }
        }
    }
}
