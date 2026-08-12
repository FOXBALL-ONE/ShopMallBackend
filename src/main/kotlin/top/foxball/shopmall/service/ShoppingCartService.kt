package top.foxball.shopmall.service

import java.math.BigDecimal
import java.time.LocalDateTime

data class ShoppingCartItemView(
    val id: Long,
    val productId: Long,
    val variantId: Long,
    val sku: String,
    val productType: String,
    val name: String,
    val color: String,
    val size: String?,
    val topSize: String?,
    val bottomSize: String?,
    val unitPrice: BigDecimal,
    val quantity: Int,
    val lineTotal: BigDecimal,
    val stock: Int,
    val productStatus: String,
    val variantStatus: String,
    val purchasable: Boolean,
    val primaryImage: String?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
)

data class ShoppingCartView(
    val customerId: Long,
    val items: List<ShoppingCartItemView>,
    val totalQuantity: Int,
    val subtotal: BigDecimal,
    val updatedAt: LocalDateTime?,
)

/** 用户购物车服务；价格与库存均从当前商品记录实时计算，加入购物车不会预占库存。 */
interface ShoppingCartService {
    fun getCart(customerId: Long): ShoppingCartView

    /** 新 SKU 创建明细，已有 SKU 则累加数量。 */
    fun addItem(customerId: Long, variantId: Long, quantity: Int): ShoppingCartView

    /** 设置明细的绝对数量；明细不存在时返回 `null`，属于其他用户时拒绝访问。 */
    fun updateItem(customerId: Long, itemId: Long, quantity: Int): ShoppingCartView?

    /** 删除当前用户的一条明细；不存在时返回 `null`，属于其他用户时拒绝访问。 */
    fun removeItem(customerId: Long, itemId: Long): ShoppingCartView?

    /** 清空购物车；尚未创建购物车时同样返回空购物车。 */
    fun clearCart(customerId: Long): ShoppingCartView
}
