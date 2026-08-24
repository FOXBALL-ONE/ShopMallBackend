package top.foxball.shopmall.service.impl

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.service.AdminProductService
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class AdminProductServiceImpl(
    private val productRepository: ProductRepository,
    private val variantRepository: ProductVariantRepository,
) : AdminProductService {
    override fun list(
        productType: String?,
        status: Product.Status?,
        deleted: Boolean?,
        keyword: String?,
        lowStock: Boolean,
        lowStockThreshold: Int,
        sortBy: AdminProductService.SortBy,
        ascending: Boolean,
        page: Int,
        size: Int,
    ): Page<Product> {
        if (page < 0 || size !in 1..100) throw ParamErrorException("分页参数无效")
        val normalizedType = productType?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val normalizedKeyword = keyword?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val specification = Specification<Product> { root, query, criteriaBuilder ->
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
            if (normalizedType != null) {
                predicates += criteriaBuilder.equal(root.get<ProductType>("productType").get<String>("code"), normalizedType)
            }
            if (status != null) predicates += criteriaBuilder.equal(root.get<Product.Status>("status"), status)
            if (deleted != null) {
                predicates += if (deleted) criteriaBuilder.isNotNull(root.get<LocalDateTime>("deletedAt"))
                else criteriaBuilder.isNull(root.get<LocalDateTime>("deletedAt"))
            }
            if (normalizedKeyword != null) {
                predicates += criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), "%$normalizedKeyword%")
            }
            if (lowStock) {
                val lowStockVariant = query.subquery(Long::class.javaObjectType)
                val variantRoot = lowStockVariant.from(ProductVariant::class.java)
                lowStockVariant.select(variantRoot.get("id")).where(
                    criteriaBuilder.equal(variantRoot.get<Product>("product"), root),
                    criteriaBuilder.lessThanOrEqualTo(variantRoot.get("warehouseVolume"), lowStockThreshold),
                )
                predicates += criteriaBuilder.exists(lowStockVariant)
            }
            if (query.resultType != java.lang.Long::class.java && query.resultType != Long::class.javaPrimitiveType) {
                val primaryOrder = when (sortBy) {
                    AdminProductService.SortBy.NAME -> {
                        val expression = criteriaBuilder.lower(root.get<String>("name"))
                        if (ascending) criteriaBuilder.asc(expression) else criteriaBuilder.desc(expression)
                    }
                    AdminProductService.SortBy.CREATED_AT -> {
                        val expression = root.get<LocalDateTime>("createdAt")
                        if (ascending) criteriaBuilder.asc(expression) else criteriaBuilder.desc(expression)
                    }
                    AdminProductService.SortBy.UPDATED_AT -> {
                        val expression = root.get<LocalDateTime>("updatedAt")
                        if (ascending) criteriaBuilder.asc(expression) else criteriaBuilder.desc(expression)
                    }
                    AdminProductService.SortBy.PRICE -> {
                        val aggregate = query.subquery(BigDecimal::class.java)
                        val variantRoot = aggregate.from(ProductVariant::class.java)
                        aggregate.select(criteriaBuilder.min(variantRoot.get("price")))
                            .where(criteriaBuilder.equal(variantRoot.get<Product>("product"), root))
                        if (ascending) criteriaBuilder.asc(aggregate) else criteriaBuilder.desc(aggregate)
                    }
                    AdminProductService.SortBy.STOCK -> {
                        val aggregate = query.subquery(Long::class.javaObjectType)
                        val variantRoot = aggregate.from(ProductVariant::class.java)
                        aggregate.select(criteriaBuilder.sumAsLong(variantRoot.get("warehouseVolume")))
                            .where(criteriaBuilder.equal(variantRoot.get<Product>("product"), root))
                        if (ascending) criteriaBuilder.asc(aggregate) else criteriaBuilder.desc(aggregate)
                    }
                    AdminProductService.SortBy.SALES -> {
                        val aggregate = query.subquery(Long::class.javaObjectType)
                        val variantRoot = aggregate.from(ProductVariant::class.java)
                        aggregate.select(criteriaBuilder.sum(variantRoot.get<Long>("salesVolume")))
                            .where(criteriaBuilder.equal(variantRoot.get<Product>("product"), root))
                        if (ascending) criteriaBuilder.asc(aggregate) else criteriaBuilder.desc(aggregate)
                    }
                }
                val idOrder = if (ascending) criteriaBuilder.asc(root.get<Long>("id")) else criteriaBuilder.desc(root.get<Long>("id"))
                query.orderBy(primaryOrder, idOrder)
            }
            criteriaBuilder.and(*predicates.toTypedArray())
        }
        return productRepository.findAll(specification, PageRequest.of(page, size)).also { products ->
            products.content.forEach { product ->
                product.productType?.code
                product.category?.id
                product.highlights.size
                product.materials.size
                product.attributes.size
                product.images.size
                product.designAndExtras.size
                product.careInstructions.size
                product.tags.size
                product.variants.forEach { it.attributes.size }
            }
        }
    }

    @Transactional
    override fun updateStatus(id: Long, status: Product.Status): Product.Status? {
        val product = productRepository.findByIdForUpdate(id) ?: return null
        if (product.deletedAt != null) throw ParamErrorException("已删除商品需先恢复")
        if (status == Product.Status.ACTIVE &&
            variantRepository.countByProduct_IdAndStatus(id, ProductVariant.Status.ACTIVE) == 0L
        ) {
            throw ParamErrorException("上架商品至少需要一个启用 SKU")
        }
        product.status = status
        return status
    }

    @Transactional
    override fun updateStatuses(ids: Collection<Long>, status: Product.Status): Int {
        val distinct = ids.distinct()
        val products = productRepository.findAllByIdForUpdate(distinct)
        if (products.size != distinct.size) throw ParamErrorException("包含不存在的商品")
        if (products.any { it.deletedAt != null }) throw ParamErrorException("包含已删除商品")
        if (status == Product.Status.ACTIVE && products.any {
                variantRepository.countByProduct_IdAndStatus(requireNotNull(it.id), ProductVariant.Status.ACTIVE) == 0L
            }
        ) {
            throw ParamErrorException("上架商品至少需要一个启用 SKU")
        }
        products.forEach { it.status = status }
        return products.size
    }

    @Transactional
    override fun softDelete(ids: Collection<Long>): Int {
        val distinct = ids.distinct()
        val products = productRepository.findAllByIdForUpdate(distinct)
        if (products.size != distinct.size) throw ParamErrorException("包含不存在的商品")
        val changed = products.filter { it.deletedAt == null }
        if (changed.isNotEmpty()) productRepository.deleteCartItemsForProducts(changed.mapNotNull(Product::id))
        changed.forEach {
            it.status = Product.Status.INACTIVE
            it.deletedAt = LocalDateTime.now()
        }
        return changed.size
    }

    @Transactional
    override fun permanentlyDelete(ids: Collection<Long>): Int {
        val distinct = ids.distinct()
        val products = productRepository.findAllByIdForUpdate(distinct)
        if (products.size != distinct.size || products.any { it.deletedAt == null }) throw ParamErrorException("永久删除只能包含已删除商品")
        productRepository.deleteCartItemsForProducts(distinct)
        productRepository.deleteReviewsForProducts(distinct)
        productRepository.deleteAll(products)
        return products.size
    }

    @Transactional
    override fun restore(id: Long): Product.Status? {
        val product = productRepository.findByIdForUpdate(id) ?: return null
        if (product.deletedAt == null) throw ParamErrorException("商品未处于已删除状态")
        product.status = Product.Status.INACTIVE
        product.deletedAt = null
        return product.status
    }

    @Transactional
    override fun restore(ids: Collection<Long>): Int = ids.distinct().count { restore(it) != null }

    @Transactional
    override fun adjustStock(variantId: Long, adjustment: Int): Int? {
        if (adjustment == 0 || adjustment !in -1_000_000..1_000_000) throw ParamErrorException("库存调整量无效")
        val variant = variantRepository.findById(variantId).orElse(null) ?: return null
        val changed = if (adjustment > 0) {
            variantRepository.increaseStock(variantId, adjustment, Int.MAX_VALUE - adjustment)
        } else {
            variantRepository.decreaseStock(variantId, -adjustment)
        }
        if (changed == 0) throw ParamErrorException("库存不足或已达到上限")
        return variantRepository.findById(variantId).orElseThrow().warehouseVolume
    }

    @Transactional
    override fun adjustStocks(variantIds: Collection<Long>, adjustment: Int): Map<Long, Int> {
        val distinctIds = variantIds.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        return distinctIds.associateWith { variantId ->
            adjustStock(variantId, adjustment) ?: throw ParamErrorException("包含不存在的 SKU")
        }
    }
}
