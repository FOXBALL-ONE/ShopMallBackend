package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.ProductCategory

interface ProductCategoryService {
    fun listAll(): List<ProductCategory>
    fun listActive(): List<ProductCategory>
    fun get(id: Long): ProductCategory?
    fun create(code: String, name: String, description: String?, parentId: Long?, displayOrder: Int, status: ProductCategory.Status): ProductCategory
    fun update(id: Long, code: String, name: String, description: String?, parentId: Long?, displayOrder: Int, status: ProductCategory.Status): ProductCategory?
    fun delete(id: Long): Boolean
}
