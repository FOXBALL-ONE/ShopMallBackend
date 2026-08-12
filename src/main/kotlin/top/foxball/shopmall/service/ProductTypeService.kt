package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.ProductAttributeDefinition
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.AttributeValueType

data class ProductTypeMutation(
    val code: String,
    val name: String,
    val description: String?,
    val active: Boolean,
    val displayOrder: Int,
)

data class AttributeDefinitionMutation(
    val code: String,
    val name: String,
    val scope: AttributeScope,
    val valueType: AttributeValueType,
    val required: Boolean,
    val filterable: Boolean,
    val allowedValues: List<String>,
    val maxLength: Int?,
    val displayOrder: Int,
    val active: Boolean,
)

interface ProductTypeService {
    fun listAll(): List<ProductType>
    fun listActive(): List<ProductType>
    fun getById(id: Long): ProductType?
    fun getByCode(code: String): ProductType?
    fun listDefinitions(typeId: Long): List<ProductAttributeDefinition>
    fun isDefinitionUsed(id: Long): Boolean
    fun create(command: ProductTypeMutation): ProductType
    fun update(id: Long, command: ProductTypeMutation): ProductType?
    fun delete(id: Long): Boolean
    fun createDefinition(typeId: Long, command: AttributeDefinitionMutation): ProductAttributeDefinition?
    fun updateDefinition(id: Long, command: AttributeDefinitionMutation): ProductAttributeDefinition?
    fun deleteDefinition(id: Long): Boolean
}
