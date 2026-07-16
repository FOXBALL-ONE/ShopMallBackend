package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.service.ProductService

@Service
@Transactional(readOnly = true)
class ProductServiceImpl(
    private val productRepository: ProductRepository,
) : ProductService {
    override fun listPublished(): List<Product> =
        productRepository.findAllByStatusOrderByCreatedAtDesc(Product.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): Product? =
        productRepository.findByIdAndStatus(id, Product.Status.ACTIVE)?.let(::hydrate)

    private fun hydrate(product: Product): Product = product.apply { hydrateBase() }
}
