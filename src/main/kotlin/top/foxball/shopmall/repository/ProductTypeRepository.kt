package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.ProductType

interface ProductTypeRepository : JpaRepository<ProductType, Long> {
    fun findAllByOrderByDisplayOrderAscCodeAsc(): List<ProductType>

    fun findAllByActiveTrueOrderByDisplayOrderAscCodeAsc(): List<ProductType>

    @EntityGraph(attributePaths = ["attributeDefinitions", "attributeDefinitions.allowedValues"])
    fun findDetailedById(id: Long): ProductType?

    @EntityGraph(attributePaths = ["attributeDefinitions", "attributeDefinitions.allowedValues"])
    fun findDetailedByCode(code: String): ProductType?

    fun existsByCode(code: String): Boolean
}
