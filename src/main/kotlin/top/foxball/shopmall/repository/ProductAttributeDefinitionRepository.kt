package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.ProductAttributeDefinition

interface ProductAttributeDefinitionRepository : JpaRepository<ProductAttributeDefinition, Long> {
    @EntityGraph(attributePaths = ["allowedValues"])
    fun findAllByProductType_IdOrderByDisplayOrderAscCodeAsc(productTypeId: Long): List<ProductAttributeDefinition>

    fun findAllByProductType_IdAndScopeAndActiveTrueOrderByDisplayOrderAscCodeAsc(
        productTypeId: Long,
        scope: AttributeScope,
    ): List<ProductAttributeDefinition>

    fun existsByProductType_IdAndCode(productTypeId: Long, code: String): Boolean
}
