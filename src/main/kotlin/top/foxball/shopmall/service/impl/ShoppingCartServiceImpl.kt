package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.CartItem
import top.foxball.shopmall.entity.jdbc.ShoppingCart
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.InsufficientStockException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.service.ShoppingCartItemView
import top.foxball.shopmall.service.ShoppingCartService
import top.foxball.shopmall.service.ShoppingCartView
import java.math.BigDecimal

@Service
@Transactional(readOnly = true)
class ShoppingCartServiceImpl(
    private val shoppingCartRepository: ShoppingCartRepository,
    private val productVariantRepository: ProductVariantRepository,
    private val userRepository: UserRepository,
) : ShoppingCartService {
    override fun getCart(customerId: Long): ShoppingCartView =
        shoppingCartRepository.findDetailedByCustomerId(customerId)?.let(::toView) ?: emptyCart(customerId)

    @Transactional
    override fun addItem(customerId: Long, variantId: Long, quantity: Int): ShoppingCartView {
        validateQuantity(quantity)
        val variant = productVariantRepository.findDetailedById(variantId)
            ?: throw ResourceNotFoundException("SKU 不存在")
        ensurePurchasable(variant, quantity)
        val cart = findOrCreateLockedCart(customerId)
        val item = cart.items.firstOrNull { it.variant?.id == variantId }
        if (item == null) {
            if (cart.items.size >= MAX_CART_LINES) throw ParamErrorException("购物车最多保存 $MAX_CART_LINES 种商品")
            cart.add(CartItem(variant = variant, quantity = quantity))
        } else {
            val targetQuantity = item.quantity + quantity
            validateQuantity(targetQuantity)
            ensurePurchasable(variant, targetQuantity)
            item.quantity = targetQuantity
        }
        return toView(shoppingCartRepository.saveAndFlush(cart))
    }

    @Transactional
    override fun updateItem(customerId: Long, itemId: Long, quantity: Int): ShoppingCartView? {
        validateQuantity(quantity)
        val cart = shoppingCartRepository.findByCustomerIdForUpdate(customerId) ?: run {
            requireItemOwner(customerId, itemId)
            return null
        }
        val item = cart.items.firstOrNull { it.id == itemId } ?: run {
            requireItemOwner(customerId, itemId)
            return null
        }
        ensurePurchasable(requireNotNull(item.variant) { "购物车明细缺少 SKU 引用" }, quantity)
        item.quantity = quantity
        return toView(shoppingCartRepository.saveAndFlush(cart))
    }

    @Transactional
    override fun removeItem(customerId: Long, itemId: Long): ShoppingCartView? {
        val cart = shoppingCartRepository.findByCustomerIdForUpdate(customerId) ?: run {
            requireItemOwner(customerId, itemId)
            return null
        }
        val item = cart.items.firstOrNull { it.id == itemId } ?: run {
            requireItemOwner(customerId, itemId)
            return null
        }
        cart.remove(item)
        return toView(shoppingCartRepository.saveAndFlush(cart))
    }

    @Transactional
    override fun clearCart(customerId: Long): ShoppingCartView {
        val cart = shoppingCartRepository.findByCustomerIdForUpdate(customerId) ?: return emptyCart(customerId)
        cart.clear()
        return toView(shoppingCartRepository.saveAndFlush(cart))
    }

    private fun toView(cart: ShoppingCart): ShoppingCartView {
        val itemViews = cart.items.map { item ->
            val variant = requireNotNull(item.variant) { "购物车明细缺少 SKU 引用" }
            val product = requireNotNull(variant.product) { "SKU 缺少商品引用" }
            val productId = requireNotNull(product.id)
            ShoppingCartItemView(
                id = requireNotNull(item.id),
                productId = productId,
                variantId = requireNotNull(variant.id),
                sku = variant.sku,
                productType = requireNotNull(product.productType).code,
                name = product.name,
                color = variant.color,
                size = variant.size,
                topSize = variant.attributes.firstOrNull { it.code == "top_size" }?.value,
                bottomSize = variant.attributes.firstOrNull { it.code == "bottom_size" }?.value,
                unitPrice = variant.price,
                quantity = item.quantity,
                lineTotal = variant.price.multiply(item.quantity.toBigDecimal()),
                stock = variant.warehouseVolume,
                productStatus = product.status.name,
                variantStatus = variant.status.name,
                purchasable = product.status == Product.Status.ACTIVE &&
                    variant.status == ProductVariant.Status.ACTIVE &&
                    product.deletedAt == null &&
                    variant.warehouseVolume >= item.quantity,
                primaryImage = product.images.firstOrNull { it.primary }?.url,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
            )
        }
        return ShoppingCartView(
            customerId = requireNotNull(cart.customer?.id),
            items = itemViews,
            totalQuantity = itemViews.sumOf(ShoppingCartItemView::quantity),
            subtotal = itemViews.fold(ZERO_AMOUNT) { total, item -> total + item.lineTotal },
            updatedAt = cart.updatedAt,
        )
    }

    private fun ensurePurchasable(variant: ProductVariant, quantity: Int) {
        val product = requireNotNull(variant.product) { "SKU 缺少商品引用" }
        if (product.status != Product.Status.ACTIVE || product.deletedAt != null || variant.status != ProductVariant.Status.ACTIVE) {
            throw ResourceNotFoundException("SKU 不存在或未上架")
        }
        if (variant.warehouseVolume < quantity) {
            throw InsufficientStockException("SKU ${variant.id} 库存不足，当前仅剩 ${variant.warehouseVolume} 件")
        }
    }

    private fun findOrCreateLockedCart(customerId: Long): ShoppingCart {
        shoppingCartRepository.findByCustomerIdForUpdate(customerId)?.let { return it }
        val customer = userRepository.findByIdForUpdate(customerId) ?: throw ResourceNotFoundException("用户不存在")
        return shoppingCartRepository.findByCustomerIdForUpdate(customerId) ?: ShoppingCart(customer = customer)
    }

    private fun requireItemOwner(customerId: Long, itemId: Long) {
        val ownerId = shoppingCartRepository.findCustomerIdByItemId(itemId)
        if (ownerId != null && ownerId != customerId) throw ForbiddenException("只能操作自己的购物车")
    }

    private fun validateQuantity(quantity: Int) {
        if (quantity !in 1..MAX_ITEM_QUANTITY) throw ParamErrorException("商品数量必须在 1 到 $MAX_ITEM_QUANTITY 之间")
    }

    private fun emptyCart(customerId: Long) = ShoppingCartView(customerId, emptyList(), 0, ZERO_AMOUNT, null)

    private companion object {
        const val MAX_CART_LINES = 50
        const val MAX_ITEM_QUANTITY = 99
        val ZERO_AMOUNT = BigDecimal("0.00")
    }
}
