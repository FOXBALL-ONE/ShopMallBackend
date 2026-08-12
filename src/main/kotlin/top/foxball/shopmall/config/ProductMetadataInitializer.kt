package top.foxball.shopmall.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.AttributeValueType
import top.foxball.shopmall.entity.jdbc.ProductAttributeDefinition
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import javax.sql.DataSource

/** 按显式配置在空库中幂等写入系统商品元数据。 */
@Component
@ConditionalOnProperty(prefix = "shopmall.product-metadata", name = ["enabled"], havingValue = "true")
class ProductMetadataInitializer(
    private val productTypeRepository: ProductTypeRepository,
    private val productCategoryRepository: ProductCategoryRepository,
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Order(0)
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun initialize() {
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
                jdbcTemplate.execute("select pg_advisory_xact_lock(734670001)")
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
        definitions().forEachIndexed { index, source ->
            val type = productTypeRepository.findDetailedByCode(source.code) ?: ProductType(
                    code = source.code,
                    name = source.name,
                    description = source.description,
                    displayOrder = index,
                )
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
        categories().forEachIndexed { index, source ->
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

    private fun definitions() = listOf(
        TypeDefinition(
            "BIKINI", "Bikini", "Two-piece swimwear",
            listOf(
                attribute("top_size", "Top size", AttributeScope.VARIANT, true, sizes()),
                attribute("bottom_size", "Bottom size", AttributeScope.VARIANT, true, sizes()),
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

    private fun attribute(code: String, name: String, scope: AttributeScope, required: Boolean, values: List<String>) =
        AttributeDefinition(code, name, scope, AttributeValueType.ENUM, required, values)

    private fun booleanAttribute(code: String, name: String) =
        AttributeDefinition(code, name, AttributeScope.PRODUCT, AttributeValueType.BOOLEAN, false, emptyList())

    private fun stringAttribute(code: String, name: String) =
        AttributeDefinition(code, name, AttributeScope.PRODUCT, AttributeValueType.STRING, false, emptyList())

    private fun sizes() = listOf("S", "M", "L", "XL", "XXL", "XXXL", "XXXXL")

    private fun categories() = listOf(
        CategoryDefinition("swimwear", "Swimwear", "Bikinis and one-piece swimwear"),
        CategoryDefinition("dresses", "Dresses", "Dress apparel"),
        CategoryDefinition("cover-ups", "Cover Ups", "Beach cover-up apparel"),
    )

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
