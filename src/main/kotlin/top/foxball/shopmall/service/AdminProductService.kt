package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.Product

/** 管理端统一 Product 查询及生命周期操作。SKU 库存通过 variantId 调整。 */
interface AdminProductService {
    enum class SortBy { CREATED_AT, UPDATED_AT, NAME, PRICE, STOCK, SALES }

    fun list(
        productType: String?,
        status: Product.Status?,
        deleted: Boolean?,
        keyword: String?,
        lowStock: Boolean,
        lowStockThreshold: Int,
        sortBy: SortBy,
        ascending: Boolean,
        page: Int,
        size: Int,
    ): Page<Product>

    fun updateStatus(id: Long, status: Product.Status): Product.Status?
    fun updateStatuses(ids: Collection<Long>, status: Product.Status): Int
    fun softDelete(ids: Collection<Long>): Int
    fun permanentlyDelete(ids: Collection<Long>): Int
    fun restore(id: Long): Product.Status?
    fun restore(ids: Collection<Long>): Int
    fun adjustStock(variantId: Long, adjustment: Int): Int?
    fun adjustStocks(variantIds: Collection<Long>, adjustment: Int): Map<Long, Int>
}
