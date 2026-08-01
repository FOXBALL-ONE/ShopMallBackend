package top.foxball.shopmall.service

import java.math.BigDecimal
import java.time.Instant

data class ShoppingCartItemView(
    val id: Long,
    val productId: Long,
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
    val purchasable: Boolean,
    val primaryImage: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class ShoppingCartView(
    val customerId: Long,
    val items: List<ShoppingCartItemView>,
    val totalQuantity: Int,
    val subtotal: BigDecimal,
    val updatedAt: Instant?,
)

/** 用户购物车服务；价格与库存均从当前商品记录实时计算，加入购物车不会预占库存。 */
interface ShoppingCartService {
    fun getCart(customerId: Long): ShoppingCartView

    /** 新商品创建明细，已有商品则累加数量。 */
    fun addItem(customerId: Long, productId: Long, quantity: Int): ShoppingCartView

    /** 设置明细的绝对数量；购物车或明细不属于当前用户时返回 `null`。 */
    fun updateItem(customerId: Long, itemId: Long, quantity: Int): ShoppingCartView?

    /** 删除当前用户的一条明细；不存在时返回 `null`。 */
    fun removeItem(customerId: Long, itemId: Long): ShoppingCartView?

    /** 清空购物车；尚未创建购物车时同样返回空购物车。 */
    fun clearCart(customerId: Long): ShoppingCartView
}
