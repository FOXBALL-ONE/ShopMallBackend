package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.Product

/** 商品图片列表中主图的轻量投影，供列表和购物车批量装载。 */
interface ProductPrimaryImage {
    val productId: Long
    val imageUrl: String
}

/** SPU 仓储。SKU 的价格、库存和销量操作必须使用 [ProductVariantRepository]。 */
interface ProductRepository : JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    fun countByStatus(status: Product.Status): Long

    fun countByStatusAndDeletedAtIsNull(status: Product.Status): Long

    fun countByDeletedAtIsNotNull(): Long

    fun findByIdAndStatus(id: Long, status: Product.Status): Product?

    @EntityGraph(attributePaths = ["productType", "category", "images", "attributes", "variants", "variants.attributes"])
    fun findDetailedByIdAndStatus(id: Long, status: Product.Status): Product?

    @EntityGraph(attributePaths = ["productType", "category", "images", "variants"])
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<Product>

    @Query(
        "select distinct p from Product p join p.variants v where p.status = :productStatus and p.deletedAt is null " +
            "and p.images is not empty and v.status = :variantStatus and v.warehouseVolume >= :minimumStock " +
            "and v.price > 0 and (:categoryId is null or p.category.id = :categoryId) " +
            "and (:productType is null or p.productType.code = :productType) " +
            "and (:tagId is null or exists (select t.id from p.tags t where t.id = :tagId)) " +
            "order by p.createdAt desc, p.updatedAt desc, p.id desc",
    )
    fun findLatestRecommendationCandidates(
        @Param("productStatus") productStatus: Product.Status,
        @Param("variantStatus") variantStatus: top.foxball.shopmall.entity.jdbc.ProductVariant.Status,
        @Param("minimumStock") minimumStock: Int,
        @Param("categoryId") categoryId: Long?,
        @Param("productType") productType: String?,
        @Param("tagId") tagId: Long?,
        pageable: org.springframework.data.domain.Pageable,
    ): List<Product>

    @Query(
        "select distinct p from Product p join p.variants v where p.status = :productStatus and p.deletedAt is null " +
            "and p.images is not empty and p.createdAt >= :createdAfter and v.status = :variantStatus " +
            "and v.warehouseVolume >= :minimumStock and v.price > 0 " +
            "and (:categoryId is null or p.category.id = :categoryId) " +
            "and (:productType is null or p.productType.code = :productType) " +
            "and (:tagId is null or exists (select t.id from p.tags t where t.id = :tagId)) " +
            "order by p.createdAt desc, p.updatedAt desc, p.id desc",
    )
    fun findNewArrivalRecommendationCandidates(
        @Param("productStatus") productStatus: Product.Status,
        @Param("variantStatus") variantStatus: top.foxball.shopmall.entity.jdbc.ProductVariant.Status,
        @Param("minimumStock") minimumStock: Int,
        @Param("categoryId") categoryId: Long?,
        @Param("productType") productType: String?,
        @Param("tagId") tagId: Long?,
        @Param("createdAfter") createdAfter: java.time.LocalDateTime,
        pageable: org.springframework.data.domain.Pageable,
    ): List<Product>

    @Query(
        "select p from Product p join p.variants v where p.status = :productStatus and p.deletedAt is null " +
            "and p.images is not empty and v.status = :variantStatus " +
            "and exists (select sv.id from ProductVariant sv where sv.product = p and sv.status = :variantStatus " +
            "and sv.warehouseVolume >= :minimumStock and sv.price > 0) " +
            "and (:categoryId is null or p.category.id = :categoryId) " +
            "and (:productType is null or p.productType.code = :productType) " +
            "and (:tagId is null or exists (select t.id from p.tags t where t.id = :tagId)) " +
            "group by p order by sum(v.salesVolume) desc, coalesce(p.score, -1.0) desc, p.createdAt desc, p.id desc",
    )
    fun findBestSellerRecommendationCandidates(
        @Param("productStatus") productStatus: Product.Status,
        @Param("variantStatus") variantStatus: top.foxball.shopmall.entity.jdbc.ProductVariant.Status,
        @Param("minimumStock") minimumStock: Int,
        @Param("categoryId") categoryId: Long?,
        @Param("productType") productType: String?,
        @Param("tagId") tagId: Long?,
        pageable: org.springframework.data.domain.Pageable,
    ): List<Product>

    @Query(
        "select p from Product p join p.variants v where p.status = :productStatus and p.deletedAt is null " +
            "and p.images is not empty and v.status = :variantStatus " +
            "and exists (select sv.id from ProductVariant sv where sv.product = p and sv.status = :variantStatus " +
            "and sv.warehouseVolume >= :minimumStock and sv.price > 0) " +
            "and (:categoryId is null or p.category.id = :categoryId) " +
            "and (:productType is null or p.productType.code = :productType) " +
            "and (:tagId is null or exists (select t.id from p.tags t where t.id = :tagId)) " +
            "group by p order by coalesce(p.score, -1.0) desc, sum(v.salesVolume) desc, " +
            "p.createdAt desc, p.id desc",
    )
    fun findHighRatedRecommendationCandidates(
        @Param("productStatus") productStatus: Product.Status,
        @Param("variantStatus") variantStatus: top.foxball.shopmall.entity.jdbc.ProductVariant.Status,
        @Param("minimumStock") minimumStock: Int,
        @Param("categoryId") categoryId: Long?,
        @Param("productType") productType: String?,
        @Param("tagId") tagId: Long?,
        pageable: org.springframework.data.domain.Pageable,
    ): List<Product>

    @EntityGraph(attributePaths = ["productType", "category"])
    fun findRecommendationProductsByIdIn(ids: Collection<Long>): List<Product>

    fun existsByTags_Id(tagId: Long): Boolean

    fun existsByProductType_Id(productTypeId: Long): Boolean

    fun existsByCategory_Id(categoryId: Long): Boolean

    @Query(
        "select (count(p) > 0) from Product p join p.attributes a " +
            "where p.productType.id = :productTypeId and a.code = :code",
    )
    fun existsByProductAttribute(
        @Param("productTypeId") productTypeId: Long,
        @Param("code") code: String,
    ): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Product?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :productIds order by p.id")
    fun findAllByIdForUpdate(@Param("productIds") productIds: Collection<Long>): List<Product>

    @Query(
        value = "select product_id as productId, image_url as imageUrl from products_images " +
            "where product_id in :productIds and is_primary = true",
        nativeQuery = true,
    )
    fun findPrimaryImagesByProductIds(@Param("productIds") productIds: Collection<Long>): List<ProductPrimaryImage>

    @Modifying(flushAutomatically = true)
    @Query("delete from CartItem i where i.variant.product.id in :productIds")
    fun deleteCartItemsForProducts(@Param("productIds") productIds: Collection<Long>): Int

    @Modifying(flushAutomatically = true)
    @Query("delete from CustomerReview r where r.product.id in :productIds")
    fun deleteReviewsForProducts(@Param("productIds") productIds: Collection<Long>): Int
}
