package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.Product

interface ProductRepository : JpaRepository<Product, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<Product>

    fun findByIdAndStatus(id: Long, status: Product.Status): Product?

    fun existsByTags_Id(tagId: Long): Boolean

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.warehouseVolume = p.warehouseVolume - :quantity " +
            "where p.id = :productId and p.warehouseVolume >= :quantity and p.status = :active",
    )
    fun decrementStock(
        @Param("productId") productId: Long,
        @Param("quantity") quantity: Int,
        @Param("active") active: Product.Status = Product.Status.ACTIVE,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.warehouseVolume = p.warehouseVolume + :quantity where p.id = :productId")
    fun restock(
        @Param("productId") productId: Long,
        @Param("quantity") quantity: Int,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.salesVolume = p.salesVolume + :quantity where p.id = :productId")
    fun incrementSales(
        @Param("productId") productId: Long,
        @Param("quantity") quantity: Int,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.salesVolume = p.salesVolume - :quantity " +
            "where p.id = :productId and p.salesVolume >= :quantity",
    )
    fun decrementSales(
        @Param("productId") productId: Long,
        @Param("quantity") quantity: Int,
    ): Int
}
