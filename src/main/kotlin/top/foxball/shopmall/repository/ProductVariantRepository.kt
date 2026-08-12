package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.ProductVariant

/** SKU 仓储。所有库存及销量变更采用条件更新，避免并发下出现负库存。 */
interface ProductVariantRepository : JpaRepository<ProductVariant, Long> {
    @EntityGraph(attributePaths = ["product", "product.productType", "product.images", "attributes"])
    fun findDetailedById(id: Long): ProductVariant?

    @EntityGraph(attributePaths = ["product", "product.productType", "product.images", "attributes"])
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id in :variantIds order by v.id")
    fun findAllDetailedByIdForUpdate(@Param("variantIds") variantIds: Collection<Long>): List<ProductVariant>

    fun findBySku(sku: String): ProductVariant?

    @Query("select v.product.id from ProductVariant v where v.id = :id")
    fun findProductIdById(@Param("id") id: Long): Long?

    fun existsBySku(sku: String): Boolean

    fun existsByProduct_IdAndOptionSignature(productId: Long, optionSignature: String): Boolean

    fun existsByProduct_IdAndOptionSignatureAndIdNot(productId: Long, optionSignature: String, id: Long): Boolean

    @Query(
        "select (count(v) > 0) from ProductVariant v join v.attributes a " +
            "where v.product.productType.id = :productTypeId and a.code = :code",
    )
    fun existsByVariantAttribute(
        @Param("productTypeId") productTypeId: Long,
        @Param("code") code: String,
    ): Boolean

    fun findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId: Long): List<ProductVariant>

    fun countByProduct_IdAndStatus(productId: Long, status: ProductVariant.Status): Long

    fun countByStatusAndWarehouseVolumeLessThanEqual(status: ProductVariant.Status, warehouseVolume: Int): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update ProductVariant v set v.warehouseVolume = v.warehouseVolume - :quantity " +
            "where v.id = :variantId and v.status = :active and v.warehouseVolume >= :quantity",
    )
    fun decrementStock(
        @Param("variantId") variantId: Long,
        @Param("quantity") quantity: Int,
        @Param("active") active: ProductVariant.Status = ProductVariant.Status.ACTIVE,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductVariant v set v.warehouseVolume = v.warehouseVolume + :quantity where v.id = :variantId")
    fun restock(@Param("variantId") variantId: Long, @Param("quantity") quantity: Int): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductVariant v set v.salesVolume = v.salesVolume + :quantity where v.id = :variantId")
    fun incrementSales(@Param("variantId") variantId: Long, @Param("quantity") quantity: Int): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update ProductVariant v set v.salesVolume = v.salesVolume - :quantity " +
            "where v.id = :variantId and v.salesVolume >= :quantity",
    )
    fun decrementSales(@Param("variantId") variantId: Long, @Param("quantity") quantity: Int): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update ProductVariant v set v.warehouseVolume = v.warehouseVolume + :quantity " +
            "where v.id = :variantId and v.warehouseVolume <= :upperBound",
    )
    fun increaseStock(
        @Param("variantId") variantId: Long,
        @Param("quantity") quantity: Int,
        @Param("upperBound") upperBound: Int,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update ProductVariant v set v.warehouseVolume = v.warehouseVolume - :quantity " +
            "where v.id = :variantId and v.warehouseVolume >= :quantity",
    )
    fun decreaseStock(@Param("variantId") variantId: Long, @Param("quantity") quantity: Int): Int
}
