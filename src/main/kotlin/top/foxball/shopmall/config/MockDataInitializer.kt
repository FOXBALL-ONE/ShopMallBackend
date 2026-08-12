package top.foxball.shopmall.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.AttributeValueType
import top.foxball.shopmall.entity.jdbc.CareInstruction
import top.foxball.shopmall.entity.jdbc.MaterialComponent
import top.foxball.shopmall.entity.jdbc.ProductAttributeDefinition
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductAttribute
import top.foxball.shopmall.entity.jdbc.ProductImage
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.entity.jdbc.ProductVariantAttribute
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.OptionSignatureService
import java.math.BigDecimal
import javax.sql.DataSource

/** 统一管理启动期的商品元数据和仅供本地开发使用的演示数据。 */
@Component
class MockDataInitializer(
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val productTypeRepository: ProductTypeRepository,
    private val productCategoryRepository: ProductCategoryRepository,
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
    private val optionSignatureService: OptionSignatureService,
    @Value($$"${shopmall.product-metadata.enabled:false}") private val productMetadataEnabled: Boolean,
    @Value($$"${shopmall.mock-data.enabled:false}") private val mockDataEnabled: Boolean,
    @Value($$"${shopmall.mock-data.password:MockData123!}") private val mockPassword: String,
) {
    @Order(0)
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun initialize() {
        if (productMetadataEnabled || mockDataEnabled) initializeProductMetadata()
        if (mockDataEnabled) initializeMockData()
    }

    private fun initializeProductMetadata() {
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
                jdbcTemplate.execute("select pg_advisory_xact_lock(734670001)")
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
        productMetadataDefinitions.forEachIndexed { index, source ->
            val type = productTypeRepository.findDetailedByCode(source.code) ?: ProductType(
                code = source.code,
                name = source.name,
                description = source.description,
                displayOrder = index,
            )
            println("开始写入")
            source.attributes.forEachIndexed { attributeIndex, attribute ->
                if (type.attributeDefinitions.none { it.code == attribute.code }) {
                    type.attributeDefinitions += ProductAttributeDefinition(
                        productType = type,
                        code = attribute.code,
                        name = attribute.name,
                        scope = attribute.scope,
                        valueType = attribute.valueType,
                        required = attribute.required,
                        allowedValues = attribute.allowedValues.toMutableList(),
                        displayOrder = attributeIndex,
                    )
                }
            }
            productTypeRepository.save(type)
        }
        productMetadataCategories.forEachIndexed { index, source ->
            if (!productCategoryRepository.existsByCode(source.code)) {
                productCategoryRepository.save(
                    ProductCategory(
                        code = source.code,
                        name = source.name,
                        description = source.description,
                        displayOrder = index,
                    ),
                )
            }
        }
    }

    private fun initializeMockData() {
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
        val swimwearCategory = productCategoryRepository
            .findAllByStatusOrderByDisplayOrderAscNameAsc(top.foxball.shopmall.entity.jdbc.ProductCategory.Status.ACTIVE)
            .firstOrNull { it.code == "swimwear" }
        val product = Product(
            productType = bikini,
            category = swimwearCategory,
            name = "Lagoon Triangle Bikini",
            status = Product.Status.ACTIVE,
            materials = mutableListOf(
                MaterialComponent("Recycled nylon", BigDecimal("82.00")),
                MaterialComponent("Elastane", BigDecimal("18.00")),
            ),
            attributes = mutableListOf(ProductAttribute("cup_style", "TRIANGLE")),
            highlights = mutableListOf("Designed for all-day comfort", "Mock catalog item"),
            images = mutableListOf(ProductImage("https://placehold.co/600x800/png?text=Lagoon+Bikini", "Blue bikini", true)),
            fitSense = "True to size with adjustable ties",
            description = "A lightweight triangle bikini with adjustable straps and a smooth, supportive finish.",
            designAndExtras = mutableListOf("Adjustable halter straps", "Fully lined construction"),
            careInstructions = mutableListOf(
                CareInstruction("Hand wash cold"),
                CareInstruction("Line dry in shade"),
            ),
        )
        val variantAttributes = mutableListOf(ProductVariantAttribute("top_size", "S"), ProductVariantAttribute("bottom_size", "S"))
        product.addVariant(
            ProductVariant(
                sku = "MOCK-BIKINI-BLUE-S",
                size = "S",
                color = "OCEAN_BLUE",
                price = BigDecimal("42.00"),
                warehouseVolume = 28,
                salesVolume = 94L,
                status = ProductVariant.Status.ACTIVE,
                attributes = variantAttributes,
                optionSignature = optionSignatureService.generate("S", "OCEAN_BLUE", variantAttributes),
            ),
        )
        productRepository.save(product)
    }

    private val sizes = listOf("S", "M", "L", "XL", "XXL", "XXXL", "XXXXL")

    private val productMetadataDefinitions = listOf(
        TypeDefinition(
            "BIKINI", "Bikini", "Two-piece swimwear",
            listOf(
                attribute("top_size", "Top size", AttributeScope.VARIANT, true, sizes),
                attribute("bottom_size", "Bottom size", AttributeScope.VARIANT, true, sizes),
                attribute("cup_style", "Cup style", AttributeScope.PRODUCT, false, listOf("TRIANGLE", "BANDEAU", "UNDERWIRE", "BALCONETTE")),
                attribute("cup_thickness", "Cup thickness", AttributeScope.PRODUCT, false, listOf("NONE", "LIGHT", "PADDED")),
                attribute("shoulder_strap_design", "Shoulder strap design", AttributeScope.PRODUCT, false, listOf("HALTER", "ADJUSTABLE", "CROSS_BACK", "OFF_SHOULDER")),
                attribute("support_structure", "Support structure", AttributeScope.PRODUCT, false, listOf("NONE", "ELASTIC", "UNDERWIRE", "BONED")),
            ),
        ),
        TypeDefinition(
            "ONE_PIECE", "One Piece", "One-piece swimwear",
            listOf(
                attribute("support_level", "Support level", AttributeScope.PRODUCT, false, listOf("LIGHT", "MEDIUM", "HIGH")),
                attribute("coverage", "Coverage", AttributeScope.PRODUCT, false, listOf("MINIMAL", "MODERATE", "FULL")),
                attribute("torso_fit", "Torso fit", AttributeScope.PRODUCT, false, listOf("SHORT", "REGULAR", "LONG")),
                attribute("neckline", "Neckline", AttributeScope.PRODUCT, false, listOf("SCOOP", "V_NECK", "SQUARE", "HALTER", "HIGH_NECK")),
                attribute("back_style", "Back style", AttributeScope.PRODUCT, false, listOf("OPEN", "CROSS_BACK", "RACERBACK", "FULL_BACK")),
                booleanAttribute("tummy_control", "Tummy control"),
                booleanAttribute("removable_padding", "Removable padding"),
                attribute("cup_style", "Cup style", AttributeScope.PRODUCT, false, listOf("SOFT", "MOULDED", "UNDERWIRE")),
                attribute("cup_thickness", "Cup thickness", AttributeScope.PRODUCT, false, listOf("NONE", "LIGHT", "PADDED")),
                attribute("shoulder_strap_design", "Shoulder strap design", AttributeScope.PRODUCT, false, listOf("HALTER", "ADJUSTABLE", "CROSS_BACK", "OFF_SHOULDER")),
                attribute("support_structure", "Support structure", AttributeScope.PRODUCT, false, listOf("NONE", "ELASTIC", "UNDERWIRE", "BONED")),
            ),
        ),
        TypeDefinition(
            "DRESS", "Dress", "Dress apparel",
            listOf(
                attribute("length", "Length", AttributeScope.PRODUCT, false, listOf("MINI", "MIDI", "MAXI")),
                attribute("silhouette", "Silhouette", AttributeScope.PRODUCT, false, listOf("A_LINE", "SHEATH", "WRAP", "SLIP", "FIT_AND_FLARE")),
                attribute("neckline", "Neckline", AttributeScope.PRODUCT, false, listOf("SCOOP", "V_NECK", "SQUARE", "HALTER", "HIGH_NECK")),
                attribute("sleeve_type", "Sleeve type", AttributeScope.PRODUCT, false, listOf("SLEEVELESS", "SHORT", "LONG", "OFF_SHOULDER")),
                stringAttribute("fabric_description", "Fabric description"),
            ),
        ),
        TypeDefinition(
            "COVER_UP", "Cover Up", "Beach cover-up apparel",
            listOf(
                attribute("cover_up_style", "Cover-up style", AttributeScope.PRODUCT, false, listOf("KIMONO", "WRAP", "TUNIC", "DUSTER")),
                attribute("sheer_level", "Sheer level", AttributeScope.PRODUCT, false, listOf("SHEER", "SEMI_SHEER", "OPAQUE")),
                stringAttribute("fabric_description", "Fabric description"),
            ),
        ),
    )

    private val productMetadataCategories = listOf(
        CategoryDefinition("swimwear", "Swimwear", "Bikinis and one-piece swimwear"),
        CategoryDefinition("dresses", "Dresses", "Dress apparel"),
        CategoryDefinition("cover-ups", "Cover Ups", "Beach cover-up apparel"),
    )

    private fun attribute(code: String, name: String, scope: AttributeScope, required: Boolean, values: List<String>) =
        AttributeDefinition(code, name, scope, AttributeValueType.ENUM, required, values)

    private fun booleanAttribute(code: String, name: String) =
        AttributeDefinition(code, name, AttributeScope.PRODUCT, AttributeValueType.BOOLEAN, false, emptyList())

    private fun stringAttribute(code: String, name: String) =
        AttributeDefinition(code, name, AttributeScope.PRODUCT, AttributeValueType.STRING, false, emptyList())

    private data class TypeDefinition(val code: String, val name: String, val description: String, val attributes: List<AttributeDefinition>)
    private data class CategoryDefinition(val code: String, val name: String, val description: String)
    private data class AttributeDefinition(
        val code: String,
        val name: String,
        val scope: AttributeScope,
        val valueType: AttributeValueType,
        val required: Boolean,
        val allowedValues: List<String>,
    )
}
