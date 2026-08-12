package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.ProductCategory

interface ProductCategoryRepository : JpaRepository<ProductCategory, Long> {
    fun findAllByOrderByDisplayOrderAscNameAsc(): List<ProductCategory>

    fun findAllByStatusOrderByDisplayOrderAscNameAsc(status: ProductCategory.Status): List<ProductCategory>

    fun existsByCode(code: String): Boolean

    fun existsByParent_Id(parentId: Long): Boolean
}
