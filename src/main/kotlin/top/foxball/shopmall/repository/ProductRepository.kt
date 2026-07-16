package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.Product

interface ProductRepository : JpaRepository<Product, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<Product>

    fun findByIdAndStatus(id: Long, status: Product.Status): Product?

    fun existsByTags_Id(tagId: Long): Boolean
}
