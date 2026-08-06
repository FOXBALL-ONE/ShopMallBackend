package top.foxball.shopmall.service.impl

import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.AdminProductService

@Service
@Transactional(readOnly = true)
class AdminProductServiceImpl(
    private val productRepository: ProductRepository,
) : AdminProductService {
    override fun list(
        productType: AdminProductService.ProductType?,
        status: Product.Status?,
        keyword: String?,
        lowStock: Boolean,
        lowStockThreshold: Int,
        sortBy: AdminProductService.SortBy,
        ascending: Boolean,
        page: Int,
        size: Int,
    ): Page<Product> {
        val normalizedKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val specification = Specification<Product> { root, _, criteriaBuilder ->
            val predicates = mutableListOf<Predicate>()
            productType?.let {
                predicates += criteriaBuilder.equal(root.type(), it.entityClass)
            }
            status?.let {
                predicates += criteriaBuilder.equal(root.get<Product.Status>("status"), it)
            }
            normalizedKeyword?.let {
                val escaped = it.lowercase()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                val pattern = "%$escaped%"
                val matches = mutableListOf<Predicate>()
                matches += criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern, '\\')
                matches += criteriaBuilder.like(criteriaBuilder.lower(root.get("color")), pattern, '\\')
                it.toLongOrNull()?.let { id ->
                    matches += criteriaBuilder.equal(root.get<Long>("id"), id)
                }
                predicates += criteriaBuilder.or(*matches.toTypedArray())
            }
            if (lowStock) {
                predicates += criteriaBuilder.lessThanOrEqualTo(root.get("warehouseVolume"), lowStockThreshold)
                if (status == null) {
                    predicates += criteriaBuilder.notEqual(root.get<Product.Status>("status"), Product.Status.DELETED)
                }
            }
            criteriaBuilder.and(*predicates.toTypedArray())
        }
        val direction = if (ascending) Sort.Direction.ASC else Sort.Direction.DESC
        var sort = Sort.by(direction, sortBy.property)
        if (sortBy.property != "id") sort = sort.and(Sort.by(Sort.Direction.DESC, "id"))
        return productRepository.findAll(specification, PageRequest.of(page, size, sort)).map {
            it.apply { hydrateBase() }
        }
    }

    @Transactional
    override fun updateStatus(id: Long, status: Product.Status): Product.Status? {
        if (status == Product.Status.DELETED) {
            throw ParamErrorException("请使用删除操作删除商品")
        }
        val product = productRepository.findById(id).orElse(null) ?: return null
        if (product.status == Product.Status.DELETED) {
            throw ParamErrorException("已删除商品需先恢复")
        }
        if (productRepository.updateAdminStatus(id, status) != 1) {
            throw ParamErrorException("商品状态已变化，请刷新后重试")
        }
        return status
    }

    @Transactional
    override fun updateStatuses(ids: Collection<Long>, status: Product.Status): Int {
        if (status == Product.Status.DELETED) {
            throw ParamErrorException("请使用批量删除操作删除商品")
        }
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) throw ParamErrorException("至少选择一个商品")
        val products = productRepository.findAllById(distinctIds)
        if (products.size != distinctIds.size) {
            throw ParamErrorException("包含不存在的商品")
        }
        if (products.any { it.status == Product.Status.DELETED }) {
            throw ParamErrorException("批量操作包含已删除商品，请先恢复")
        }
        if (productRepository.updateAdminStatuses(distinctIds, status) != distinctIds.size) {
            throw ParamErrorException("部分商品状态已变化，请刷新后重试")
        }
        return distinctIds.size
    }

    @Transactional
    override fun softDelete(ids: Collection<Long>): Int {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) throw ParamErrorException("至少选择一个商品")
        if (productRepository.findAllById(distinctIds).size != distinctIds.size) {
            throw ParamErrorException("包含不存在的商品")
        }
        return productRepository.softDeleteAdminProducts(distinctIds)
    }

    @Transactional
    override fun permanentlyDelete(ids: Collection<Long>): Int {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) throw ParamErrorException("至少选择一个商品")
        val products = productRepository.findAllByIdForUpdate(distinctIds)
        if (products.size != distinctIds.size) {
            throw ParamErrorException("包含不存在的商品")
        }
        if (products.any { it.status != Product.Status.DELETED }) {
            throw ParamErrorException("永久删除只能包含已逻辑删除商品")
        }
        productRepository.deleteAdminCartItemsForProducts(distinctIds)
        productRepository.deleteAdminReviewsForProducts(distinctIds)
        productRepository.deleteAll(products)
        productRepository.flush()
        return distinctIds.size
    }

    @Transactional
    override fun restore(id: Long): Product.Status? {
        val product = productRepository.findById(id).orElse(null) ?: return null
        if (product.status != Product.Status.DELETED) {
            throw ParamErrorException("商品未处于已删除状态")
        }
        if (productRepository.restoreAdminProduct(id) != 1) {
            throw ParamErrorException("商品状态已变化，请刷新后重试")
        }
        return Product.Status.INACTIVE
    }

    @Transactional
    override fun restore(ids: Collection<Long>): Int {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) throw ParamErrorException("至少选择一个商品")
        val products = productRepository.findAllById(distinctIds)
        if (products.size != distinctIds.size) {
            throw ParamErrorException("包含不存在的商品")
        }
        if (products.any { it.status != Product.Status.DELETED }) {
            throw ParamErrorException("批量恢复只能包含已删除商品")
        }
        if (productRepository.restoreAdminProducts(distinctIds) != distinctIds.size) {
            throw ParamErrorException("部分商品状态已变化，请刷新后重试")
        }
        return distinctIds.size
    }

    @Transactional
    override fun adjustStock(id: Long, adjustment: Int): Int? {
        if (adjustment == 0 || adjustment !in -1_000_000..1_000_000) {
            throw ParamErrorException("库存调整量必须在 -1000000 到 1000000 之间且不能为 0")
        }
        val product = productRepository.findById(id).orElse(null) ?: return null
        if (product.status == Product.Status.DELETED) {
            throw ParamErrorException("已删除商品不能调整库存")
        }
        val changed = if (adjustment > 0) {
            productRepository.increaseAdminStock(id, adjustment, Int.MAX_VALUE - adjustment)
        } else {
            productRepository.decreaseAdminStock(id, -adjustment)
        }
        if (changed == 0) {
            throw ParamErrorException(if (adjustment < 0) "库存不足，无法完成调整" else "库存已达到上限")
        }
        return productRepository.findById(id).orElseThrow().warehouseVolume
    }
}
