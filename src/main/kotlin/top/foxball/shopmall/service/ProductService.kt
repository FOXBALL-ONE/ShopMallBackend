package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import java.math.BigDecimal

data class ProductAttributeInput(val code: String, val value: String)
data class ProductImageInput(val url: String, val altText: String?, val primary: Boolean)
data class ProductMaterialInput(val name: String, val percentage: BigDecimal)
data class ProductVariantInput(
    val sku: String,
    val size: String?,
    val color: String,
    val price: BigDecimal,
    val warehouseVolume: Int,
    val status: ProductVariant.Status,
    val displayOrder: Int,
    val attributes: List<ProductAttributeInput>,
    val id: Long? = null,
)

data class CreateProductCommand(
    val productTypeId: Long,
    val categoryId: Long?,
    val name: String,
    val status: Product.Status,
    val highlights: List<String>,
    val materials: List<ProductMaterialInput>,
    val attributes: List<ProductAttributeInput>,
    val images: List<ProductImageInput>,
    val fitSense: String?,
    val description: String?,
    val designAndExtras: List<String>,
    val careInstructions: List<String>,
    val tagIds: Set<Long>,
    val variants: List<ProductVariantInput>,
)

data class UpdateProductCommand(
    val categoryId: Long?,
    val name: String,
    val status: Product.Status,
    val highlights: List<String>,
    val materials: List<ProductMaterialInput>,
    val attributes: List<ProductAttributeInput>,
    val images: List<ProductImageInput>,
    val fitSense: String?,
    val description: String?,
    val designAndExtras: List<String>,
    val careInstructions: List<String>,
    val tagIds: Set<Long>,
    val variants: List<ProductVariantInput>,
)

/** 面向客户的统一商品读取服务。交易信息始终通过 SKU 返回。 */
interface ProductService {
    fun listPublished(
        productTypeCode: String? = null,
        categoryId: Long? = null,
        keyword: String? = null,
        page: Int = 0,
        size: Int = 24,
    ): Page<Product>

    fun getPublished(id: Long): Product?

    fun getAdmin(id: Long): Product?

    fun create(command: CreateProductCommand): Product

    fun update(id: Long, command: UpdateProductCommand): Product?
}
