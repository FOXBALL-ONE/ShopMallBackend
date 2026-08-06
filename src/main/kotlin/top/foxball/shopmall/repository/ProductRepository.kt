package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.Product

/** 商品图片列表中排序第一的图片。 */
interface ProductPrimaryImage {
    val productId: Long
    val imageUrl: String
}

interface ProductRepository : JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    fun countByStatus(status: Product.Status): Long

    fun countByWarehouseVolumeLessThanEqualAndStatus(warehouseVolume: Int, status: Product.Status): Long

    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<Product>

    fun findByIdAndStatus(id: Long, status: Product.Status): Product?

    fun existsByTags_Id(tagId: Long): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :productIds order by p.id")
    fun findAllByIdForUpdate(@Param("productIds") productIds: Collection<Long>): List<Product>

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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.status = :status, p.updatedAt = CURRENT_TIMESTAMP " +
            "where p.id = :productId and p.status <> :deleted",
    )
    fun updateAdminStatus(
        @Param("productId") productId: Long,
        @Param("status") status: Product.Status,
        @Param("deleted") deleted: Product.Status = Product.Status.DELETED,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.status = :status, p.updatedAt = CURRENT_TIMESTAMP " +
            "where p.id in :productIds and p.status <> :deleted",
    )
    fun updateAdminStatuses(
        @Param("productIds") productIds: Collection<Long>,
        @Param("status") status: Product.Status,
        @Param("deleted") deleted: Product.Status = Product.Status.DELETED,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.status = :deleted, p.updatedAt = CURRENT_TIMESTAMP " +
            "where p.id in :productIds and p.status <> :deleted",
    )
    fun softDeleteAdminProducts(
        @Param("productIds") productIds: Collection<Long>,
        @Param("deleted") deleted: Product.Status = Product.Status.DELETED,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from CartItem i where i.product.id in :productIds")
    fun deleteAdminCartItemsForProducts(@Param("productIds") productIds: Collection<Long>): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from CustomerReview r where r.product.id in :productIds")
    fun deleteAdminReviewsForProducts(@Param("productIds") productIds: Collection<Long>): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.status = :inactive, p.updatedAt = CURRENT_TIMESTAMP " +
            "where p.id = :productId and p.status = :deleted",
    )
    fun restoreAdminProduct(
        @Param("productId") productId: Long,
        @Param("inactive") inactive: Product.Status = Product.Status.INACTIVE,
        @Param("deleted") deleted: Product.Status = Product.Status.DELETED,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.status = :inactive, p.updatedAt = CURRENT_TIMESTAMP " +
            "where p.id in :productIds and p.status = :deleted",
    )
    fun restoreAdminProducts(
        @Param("productIds") productIds: Collection<Long>,
        @Param("inactive") inactive: Product.Status = Product.Status.INACTIVE,
        @Param("deleted") deleted: Product.Status = Product.Status.DELETED,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.warehouseVolume = p.warehouseVolume + :quantity, " +
            "p.updatedAt = CURRENT_TIMESTAMP where p.id = :productId and p.status <> :deleted " +
            "and p.warehouseVolume <= :upperBound",
    )
    fun increaseAdminStock(
        @Param("productId") productId: Long,
        @Param("quantity") quantity: Int,
        @Param("upperBound") upperBound: Int,
        @Param("deleted") deleted: Product.Status = Product.Status.DELETED,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Product p set p.warehouseVolume = p.warehouseVolume - :quantity, " +
            "p.updatedAt = CURRENT_TIMESTAMP where p.id = :productId and p.status <> :deleted " +
            "and p.warehouseVolume >= :quantity",
    )
    fun decreaseAdminStock(
        @Param("productId") productId: Long,
        @Param("quantity") quantity: Int,
        @Param("deleted") deleted: Product.Status = Product.Status.DELETED,
    ): Int
}
