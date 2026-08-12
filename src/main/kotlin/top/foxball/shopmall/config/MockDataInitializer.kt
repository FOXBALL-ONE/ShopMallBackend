package top.foxball.shopmall.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductAttribute
import top.foxball.shopmall.entity.jdbc.ProductImage
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.entity.jdbc.ProductVariantAttribute
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.OptionSignatureService
import java.math.BigDecimal

/** 仅供本地开发的统一商品演示数据，不再写入旧品类实体或历史订单。 */
@Component
@ConditionalOnProperty(prefix = "shopmall.mock-data", name = ["enabled"], havingValue = "true")
class MockDataInitializer(
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val productTypeRepository: ProductTypeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val optionSignatureService: OptionSignatureService,
    @Value($$"${shopmall.mock-data.password:MockData123!}") private val mockPassword: String,
) {
    @Order(1)
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun initializeMockData() {
        if (userRepository.findByUsername("mock_admin") == null) {
            userRepository.save(
                User(
                    username = "mock_admin",
                    email = "mock.admin@shopmall.local",
                    password = requireNotNull(passwordEncoder.encode(mockPassword)),
                    firstName = "Mock",
                    lastName = "Administrator",
                    role = Role.ADMIN,
                    status = Status.ACTIVE,
                    emailVerified = true,
                    currency = "USD",
                ),
            )
        }
        if (productRepository.count() > 0) return
        val bikini = productTypeRepository.findDetailedByCode("BIKINI") ?: return
        val product = Product(
            productType = bikini,
            name = "Lagoon Triangle Bikini",
            status = Product.Status.ACTIVE,
            attributes = mutableListOf(ProductAttribute("cup_style", "TRIANGLE")),
            highlights = mutableListOf("Designed for all-day comfort", "Mock catalog item"),
            images = mutableListOf(ProductImage("https://placehold.co/600x800/png?text=Lagoon+Bikini", "Blue bikini", true)),
        )
        val variantAttributes = mutableListOf(ProductVariantAttribute("top_size", "S"), ProductVariantAttribute("bottom_size", "S"))
        product.addVariant(
            ProductVariant(
                sku = "MOCK-BIKINI-BLUE-S",
                color = "OCEAN_BLUE",
                price = BigDecimal("42.00"),
                warehouseVolume = 28,
                salesVolume = 94,
                status = ProductVariant.Status.ACTIVE,
                attributes = variantAttributes,
                optionSignature = optionSignatureService.generate(null, "OCEAN_BLUE", variantAttributes),
            ),
        )
        productRepository.save(product)
    }
}
