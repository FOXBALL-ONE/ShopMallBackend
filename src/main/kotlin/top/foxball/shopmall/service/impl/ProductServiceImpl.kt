package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.ProductService

/**
 * [ProductService] 的实现：按上架状态与创建时间跨品类聚合读取商品，
 * 返回前在事务内 hydrate 延迟集合，供前台统一展示。
 */
@Service
@Transactional(readOnly = true)
class ProductServiceImpl(
    private val productRepository: ProductRepository,
) : ProductService {
    override fun listPublished(): List<Product> =
        productRepository.findAllByStatusOrderByCreatedAtDesc(Product.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): Product? =
        productRepository.findByIdAndStatus(id, Product.Status.ACTIVE)?.let(::hydrate)

    /** 在事务内初始化前台详情所需的公共延迟集合，避免控制器序列化时访问已关闭会话。 */
    private fun hydrate(product: Product): Product = product.apply { hydrateBase() }
}
