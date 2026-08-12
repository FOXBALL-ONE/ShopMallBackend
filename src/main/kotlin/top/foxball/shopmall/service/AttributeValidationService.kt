package top.foxball.shopmall.service

import org.springframework.stereotype.Service
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.AttributeValueType
import top.foxball.shopmall.entity.jdbc.ProductAttribute
import top.foxball.shopmall.entity.jdbc.ProductAttributeDefinition
import top.foxball.shopmall.entity.jdbc.ProductVariantAttribute
import top.foxball.shopmall.repository.ProductAttributeDefinitionRepository
import top.foxball.shopmall.handler.ParamErrorException

@Service
class AttributeValidationService(
    private val definitionRepository: ProductAttributeDefinitionRepository,
) {
    fun validateProduct(typeId: Long, values: Collection<ProductAttribute>): List<ProductAttribute> {
        val definitions = definitions(typeId, AttributeScope.PRODUCT)
        return validateCodesAndValues(definitions, values.map { it.code to it.value })
            .map { ProductAttribute(it.first, it.second) }
    }

    fun validateVariant(typeId: Long, values: Collection<ProductVariantAttribute>): List<ProductVariantAttribute> {
        val definitions = definitions(typeId, AttributeScope.VARIANT)
        val normalized = validateCodesAndValues(definitions, values.map { it.code to it.value })
        return normalized.map { ProductVariantAttribute(it.first, it.second) }
    }

    private fun definitions(typeId: Long, scope: AttributeScope): Map<String, ProductAttributeDefinition> =
        definitionRepository.findAllByProductType_IdAndScopeAndActiveTrueOrderByDisplayOrderAscCodeAsc(typeId, scope)
            .associateBy { it.code }

    private fun validateCodesAndValues(
        definitions: Map<String, ProductAttributeDefinition>,
        values: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val normalizedCodes = values.map { it.first.trim().lowercase() to it.second.trim() }
        if (normalizedCodes.any { it.first.isBlank() || it.second.isBlank() }) throw ParamErrorException("属性 code 和 value 不能为空")
        if (normalizedCodes.map { it.first }.distinct().size != normalizedCodes.size) throw ParamErrorException("属性 code 不能重复")
        val normalizedValues = normalizedCodes.map { (code, rawValue) ->
            val definition = definitions[code] ?: throw ParamErrorException("属性未定义或已停用: $code")
            val value = when (definition.valueType) {
                AttributeValueType.STRING -> rawValue
                AttributeValueType.BOOLEAN -> rawValue.lowercase().also {
                    if (it != "true" && it != "false") throw ParamErrorException("属性 $code 必须为 true 或 false")
                }
                AttributeValueType.INTEGER -> rawValue.toLongOrNull()?.toString()
                    ?: throw ParamErrorException("属性 $code 必须为整数")
                AttributeValueType.DECIMAL -> rawValue.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
                    ?: throw ParamErrorException("属性 $code 必须为数字")
                AttributeValueType.ENUM -> rawValue.uppercase().also {
                    if (it !in definition.allowedValues) throw ParamErrorException("属性 $code 值不在允许范围内")
                }
            }
            if (definition.maxLength != null && value.length > definition.maxLength!!) {
                throw ParamErrorException("属性 $code 超过长度限制")
            }
            code to value
        }
        definitions.values.filter { it.required }.forEach { definition ->
            if (normalizedValues.none { it.first == definition.code }) throw ParamErrorException("缺少必填属性: ${definition.code}")
        }
        return normalizedValues
    }
}
