package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.ProductAttributeDefinition
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.repository.ProductAttributeDefinitionRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.entity.jdbc.AttributeValueType
import top.foxball.shopmall.service.AttributeDefinitionMutation
import top.foxball.shopmall.service.ProductTypeMutation
import top.foxball.shopmall.service.ProductTypeService

@Service
@Transactional(readOnly = true)
class ProductTypeServiceImpl(
    private val productTypeRepository: ProductTypeRepository,
    private val definitionRepository: ProductAttributeDefinitionRepository,
    private val productRepository: ProductRepository,
    private val variantRepository: ProductVariantRepository,
) : ProductTypeService {
    override fun listAll(): List<ProductType> = productTypeRepository.findAllByOrderByDisplayOrderAscCodeAsc()

    override fun listActive(): List<ProductType> = productTypeRepository.findAllByActiveTrueOrderByDisplayOrderAscCodeAsc()

    override fun getById(id: Long): ProductType? = productTypeRepository.findDetailedById(id)

    override fun getByCode(code: String): ProductType? = productTypeRepository.findDetailedByCode(code.trim().uppercase())

    override fun listDefinitions(typeId: Long): List<ProductAttributeDefinition> =
        definitionRepository.findAllByProductType_IdOrderByDisplayOrderAscCodeAsc(typeId)

    override fun isDefinitionUsed(id: Long): Boolean {
        val definition = definitionRepository.findById(id).orElse(null) ?: return false
        val typeId = requireNotNull(definition.productType?.id)
        return productRepository.existsByProductAttribute(typeId, definition.code) ||
            variantRepository.existsByVariantAttribute(typeId, definition.code)
    }

    @Transactional
    override fun create(command: ProductTypeMutation): ProductType {
        val code = normalizeTypeCode(command.code)
        validateTypeMutation(command)
        if (productTypeRepository.existsByCode(code)) throw ParamErrorException("商品类型 code 已存在")
        return productTypeRepository.saveAndFlush(
            ProductType(
                code = code,
                name = command.name.trim(),
                description = command.description?.trim()?.takeIf(String::isNotEmpty),
                active = command.active,
                displayOrder = command.displayOrder,
            ),
        )
    }

    @Transactional
    override fun update(id: Long, command: ProductTypeMutation): ProductType? {
        val type = productTypeRepository.findById(id).orElse(null) ?: return null
        if (normalizeTypeCode(command.code) != type.code) throw ParamErrorException("商品类型 code 创建后不能修改")
        validateTypeMutation(command)
        type.name = command.name.trim()
        type.description = command.description?.trim()?.takeIf(String::isNotEmpty)
        type.active = command.active
        type.displayOrder = command.displayOrder
        return productTypeRepository.saveAndFlush(type)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val type = productTypeRepository.findById(id).orElse(null) ?: return false
        if (productRepository.existsByProductType_Id(id)) throw ParamErrorException("商品类型已被商品使用，只能停用")
        productTypeRepository.delete(type)
        productTypeRepository.flush()
        return true
    }

    @Transactional
    override fun createDefinition(typeId: Long, command: AttributeDefinitionMutation): ProductAttributeDefinition? {
        val type = productTypeRepository.findById(typeId).orElse(null) ?: return null
        val normalized = normalizeDefinition(command)
        if (definitionRepository.existsByProductType_IdAndCode(typeId, normalized.code)) {
            throw ParamErrorException("属性 code 已存在")
        }
        return definitionRepository.saveAndFlush(
            ProductAttributeDefinition(
                productType = type,
                code = normalized.code,
                name = normalized.name,
                scope = normalized.scope,
                valueType = normalized.valueType,
                required = normalized.required,
                filterable = normalized.filterable,
                allowedValues = normalized.allowedValues.toMutableList(),
                maxLength = normalized.maxLength,
                displayOrder = normalized.displayOrder,
                active = normalized.active,
            ),
        )
    }

    @Transactional
    override fun updateDefinition(id: Long, command: AttributeDefinitionMutation): ProductAttributeDefinition? {
        val definition = definitionRepository.findById(id).orElse(null) ?: return null
        val normalized = normalizeDefinition(command)
        if (normalized.code != definition.code) throw ParamErrorException("属性 code 创建后不能修改")
        val typeId = requireNotNull(definition.productType?.id)
        val used = definition.scope == AttributeScope.PRODUCT &&
            productRepository.existsByProductAttribute(typeId, definition.code) ||
            definition.scope == AttributeScope.VARIANT &&
            variantRepository.existsByVariantAttribute(typeId, definition.code)
        if (used && (
                definition.scope != normalized.scope ||
                    definition.valueType != normalized.valueType ||
                    definition.required != normalized.required ||
                    definition.allowedValues != normalized.allowedValues ||
                    definition.maxLength != normalized.maxLength ||
                    definition.active != normalized.active
                )
        ) {
            throw ParamErrorException("已使用属性不能修改作用域、值规则、必填或启用状态")
        }
        definition.name = normalized.name
        definition.scope = normalized.scope
        definition.valueType = normalized.valueType
        definition.required = normalized.required
        definition.filterable = normalized.filterable
        definition.allowedValues.clear()
        definition.allowedValues.addAll(normalized.allowedValues)
        definition.maxLength = normalized.maxLength
        definition.displayOrder = normalized.displayOrder
        definition.active = normalized.active
        return definitionRepository.saveAndFlush(definition)
    }

    @Transactional
    override fun deleteDefinition(id: Long): Boolean {
        val definition = definitionRepository.findById(id).orElse(null) ?: return false
        val typeId = requireNotNull(definition.productType?.id)
        val used = productRepository.existsByProductAttribute(typeId, definition.code) ||
            variantRepository.existsByVariantAttribute(typeId, definition.code)
        if (used) throw ParamErrorException("属性已被商品或 SKU 使用，只能停用")
        definitionRepository.delete(definition)
        definitionRepository.flush()
        return true
    }

    private fun normalizeTypeCode(code: String): String {
        val normalized = code.trim().uppercase()
        if (!TYPE_CODE.matches(normalized)) throw ParamErrorException("商品类型 code 必须使用大写下划线格式")
        return normalized
    }

    private fun validateTypeMutation(command: ProductTypeMutation) {
        if (command.name.trim().isEmpty() || command.name.trim().length > 100) throw ParamErrorException("商品类型名称无效")
        if ((command.description?.trim()?.length ?: 0) > 1_000) throw ParamErrorException("商品类型说明不能超过 1000 个字符")
        if (command.displayOrder < 0) throw ParamErrorException("排序值不能小于 0")
    }

    private fun normalizeDefinition(command: AttributeDefinitionMutation): AttributeDefinitionMutation {
        val code = command.code.trim().lowercase()
        if (!ATTRIBUTE_CODE.matches(code)) throw ParamErrorException("属性 code 必须使用 snake_case")
        val name = command.name.trim()
        if (name.isEmpty() || name.length > 100) throw ParamErrorException("属性名称无效")
        if (command.displayOrder < 0) throw ParamErrorException("排序值不能小于 0")
        if (command.maxLength != null && command.maxLength !in 1..1_000) throw ParamErrorException("属性最大长度必须在 1 到 1000 之间")
        val allowedValues = command.allowedValues.map(String::trim).filter(String::isNotEmpty).map(String::uppercase).distinct()
        if (command.valueType == AttributeValueType.ENUM && allowedValues.isEmpty()) throw ParamErrorException("枚举属性必须提供允许值")
        if (command.valueType != AttributeValueType.ENUM && allowedValues.isNotEmpty()) throw ParamErrorException("只有枚举属性可以配置允许值")
        return command.copy(code = code, name = name, allowedValues = allowedValues)
    }

    private companion object {
        val TYPE_CODE = Regex("^[A-Z][A-Z0-9_]*$")
        val ATTRIBUTE_CODE = Regex("^[a-z][a-z0-9_]*$")
    }
}
