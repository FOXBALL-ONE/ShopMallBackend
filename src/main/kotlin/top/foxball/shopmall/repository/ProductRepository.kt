package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.Product

/** 商品图片列表中排序第一的图片。 */
interface ProductPrimaryImage {
    val productId: Long
    val imageUrl: String
}

interface ProductRepository : JpaRepository<Product, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<Product>

    fun findByIdAndStatus(id: Long, status: Product.Status): Product?

    fun existsByTags_Id(tagId: Long): Boolean

    /** 批量读取商品主图，避免购物车逐条初始化 [Product.images] 造成 N+1 查询。 */
    @Query(
        value = "select product_id as productId, image_url as imageUrl from products_images " +
            "where product_id in :productIds and sort_order = 0",
        nativeQuery = true,
    )
    fun findPrimaryImagesByProductIds(@Param("productIds") productIds: Collection<Long>): List<ProductPrimaryImage>

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
