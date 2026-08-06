package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product

/** 管理端跨品类商品查询与库存、生命周期操作。 */
interface AdminProductService {
    enum class ProductType(val entityClass: Class<out Product>) {
        BIKINI(BikiniSuit::class.java),
        ONE_PIECE(OnePieceSuit::class.java),
        DRESS(Dress::class.java),
        COVER_UP(CoverUp::class.java),
    }

    enum class SortBy(val property: String) {
        CREATED_AT("createdAt"),
        UPDATED_AT("updatedAt"),
        NAME("name"),
        PRICE("price"),
        STOCK("warehouseVolume"),
        SALES("salesVolume"),
    }

    fun list(
        productType: ProductType?,
        status: Product.Status?,
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

    fun adjustStock(id: Long, adjustment: Int): Int?
}
