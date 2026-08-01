package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CartItem
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ShoppingCart
import top.foxball.shopmall.handler.InsufficientStockException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.ShoppingCartItemView
import top.foxball.shopmall.service.ShoppingCartService
import top.foxball.shopmall.service.ShoppingCartView
import java.math.BigDecimal
import java.time.Instant

@Service
@Transactional(readOnly = true)
class ShoppingCartServiceImpl(
    private val shoppingCartRepository: ShoppingCartRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
) : ShoppingCartService {
    override fun getCart(customerId: Long): ShoppingCartView {
        val cart = shoppingCartRepository.findDetailedByCustomerId(customerId) ?: return ShoppingCartView(
            customerId = customerId,
            items = emptyList(),
            totalQuantity = 0,
            subtotal = ZERO_AMOUNT,
            updatedAt = null,
        )
        val productIds = cart.items.map { requireNotNull(it.product?.id) }.distinct()
        val primaryImages = if (productIds.isEmpty()) {
            emptyMap()
        } else {
            productRepository.findPrimaryImagesByProductIds(productIds)
                .associate { image -> image.productId to image.imageUrl }
        }
        val itemViews = cart.items.map { item ->
            val product = requireNotNull(item.product) { "购物车明细缺少商品引用" }
            val productId = requireNotNull(product.id)
            val unitPrice = product.price
            ShoppingCartItemView(
                id = requireNotNull(item.id),
                productId = productId,
                productType = product.productType(),
                name = product.name,
                color = product.color,
                size = product.sizeName(),
                topSize = (product as? BikiniSuit)?.topSize?.name,
                bottomSize = (product as? BikiniSuit)?.bottomSize?.name,
                unitPrice = unitPrice,
                quantity = item.quantity,
                lineTotal = unitPrice.multiply(item.quantity.toBigDecimal()),
                stock = product.warehouseVolume,
                productStatus = product.status.name,
                purchasable = product.status == Product.Status.ACTIVE && product.warehouseVolume >= item.quantity,
                primaryImage = primaryImages[productId],
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

    @Transactional
    override fun addItem(customerId: Long, productId: Long, quantity: Int): ShoppingCartView {
        validateQuantity(quantity)
        val product = productRepository.findByIdAndStatus(productId, Product.Status.ACTIVE)
            ?: throw ResourceNotFoundException("商品不存在或未上架")
        val cart = findOrCreateLockedCart(customerId)
        val item = cart.items.firstOrNull { it.product?.id == productId }

        if (item == null) {
            if (cart.items.size >= MAX_CART_LINES) {
                throw ParamErrorException("购物车最多保存 $MAX_CART_LINES 种商品")
            }
            ensureStock(product, quantity)
            cart.add(CartItem(product = product, quantity = quantity))
        } else {
            val targetQuantity = item.quantity + quantity
            validateQuantity(targetQuantity)
            ensureStock(product, targetQuantity)
            item.quantity = targetQuantity
        }

        cart.updatedAt = Instant.now()
        val savedCart = shoppingCartRepository.saveAndFlush(cart)
        val productIds = savedCart.items.map { requireNotNull(it.product?.id) }.distinct()
        val primaryImages = productRepository.findPrimaryImagesByProductIds(productIds)
            .associate { image -> image.productId to image.imageUrl }
        val itemViews = savedCart.items.map { savedItem ->
            val savedProduct = requireNotNull(savedItem.product) { "购物车明细缺少商品引用" }
            val savedProductId = requireNotNull(savedProduct.id)
            val unitPrice = savedProduct.price
            ShoppingCartItemView(
                id = requireNotNull(savedItem.id),
                productId = savedProductId,
                productType = savedProduct.productType(),
                name = savedProduct.name,
                color = savedProduct.color,
                size = savedProduct.sizeName(),
                topSize = (savedProduct as? BikiniSuit)?.topSize?.name,
                bottomSize = (savedProduct as? BikiniSuit)?.bottomSize?.name,
                unitPrice = unitPrice,
                quantity = savedItem.quantity,
                lineTotal = unitPrice.multiply(savedItem.quantity.toBigDecimal()),
                stock = savedProduct.warehouseVolume,
                productStatus = savedProduct.status.name,
                purchasable = savedProduct.status == Product.Status.ACTIVE &&
                    savedProduct.warehouseVolume >= savedItem.quantity,
                primaryImage = primaryImages[savedProductId],
                createdAt = savedItem.createdAt,
                updatedAt = savedItem.updatedAt,
            )
        }
        return ShoppingCartView(
            customerId = requireNotNull(savedCart.customer?.id),
            items = itemViews,
            totalQuantity = itemViews.sumOf(ShoppingCartItemView::quantity),
            subtotal = itemViews.fold(ZERO_AMOUNT) { total, savedItem -> total + savedItem.lineTotal },
            updatedAt = savedCart.updatedAt,
        )
    }

    @Transactional
    override fun updateItem(customerId: Long, itemId: Long, quantity: Int): ShoppingCartView? {
        validateQuantity(quantity)
        val cart = shoppingCartRepository.findByCustomerIdForUpdate(customerId) ?: return null
        val item = cart.items.firstOrNull { it.id == itemId } ?: return null
        val product = requireNotNull(item.product) { "购物车明细缺少商品引用" }
        if (product.status != Product.Status.ACTIVE) {
            throw ResourceNotFoundException("商品不存在或未上架")
        }
        ensureStock(product, quantity)
        item.quantity = quantity
        cart.updatedAt = Instant.now()
        val savedCart = shoppingCartRepository.saveAndFlush(cart)
        val productIds = savedCart.items.map { requireNotNull(it.product?.id) }.distinct()
        val primaryImages = productRepository.findPrimaryImagesByProductIds(productIds)
            .associate { image -> image.productId to image.imageUrl }
        val itemViews = savedCart.items.map { savedItem ->
            val savedProduct = requireNotNull(savedItem.product) { "购物车明细缺少商品引用" }
            val savedProductId = requireNotNull(savedProduct.id)
            val unitPrice = savedProduct.price
            ShoppingCartItemView(
                id = requireNotNull(savedItem.id),
                productId = savedProductId,
                productType = savedProduct.productType(),
                name = savedProduct.name,
                color = savedProduct.color,
                size = savedProduct.sizeName(),
                topSize = (savedProduct as? BikiniSuit)?.topSize?.name,
                bottomSize = (savedProduct as? BikiniSuit)?.bottomSize?.name,
                unitPrice = unitPrice,
                quantity = savedItem.quantity,
                lineTotal = unitPrice.multiply(savedItem.quantity.toBigDecimal()),
                stock = savedProduct.warehouseVolume,
                productStatus = savedProduct.status.name,
                purchasable = savedProduct.status == Product.Status.ACTIVE &&
                    savedProduct.warehouseVolume >= savedItem.quantity,
                primaryImage = primaryImages[savedProductId],
                createdAt = savedItem.createdAt,
                updatedAt = savedItem.updatedAt,
            )
        }
        return ShoppingCartView(
            customerId = requireNotNull(savedCart.customer?.id),
            items = itemViews,
            totalQuantity = itemViews.sumOf(ShoppingCartItemView::quantity),
            subtotal = itemViews.fold(ZERO_AMOUNT) { total, savedItem -> total + savedItem.lineTotal },
            updatedAt = savedCart.updatedAt,
        )
    }

    @Transactional
    override fun removeItem(customerId: Long, itemId: Long): ShoppingCartView? {
        val cart = shoppingCartRepository.findByCustomerIdForUpdate(customerId) ?: return null
        val item = cart.items.firstOrNull { it.id == itemId } ?: return null
        cart.remove(item)
        cart.updatedAt = Instant.now()
        val savedCart = shoppingCartRepository.saveAndFlush(cart)
        val productIds = savedCart.items.map { requireNotNull(it.product?.id) }.distinct()
        val primaryImages = if (productIds.isEmpty()) {
            emptyMap()
        } else {
            productRepository.findPrimaryImagesByProductIds(productIds)
                .associate { image -> image.productId to image.imageUrl }
        }
        val itemViews = savedCart.items.map { savedItem ->
            val savedProduct = requireNotNull(savedItem.product) { "购物车明细缺少商品引用" }
            val savedProductId = requireNotNull(savedProduct.id)
            val unitPrice = savedProduct.price
            ShoppingCartItemView(
                id = requireNotNull(savedItem.id),
                productId = savedProductId,
                productType = savedProduct.productType(),
                name = savedProduct.name,
                color = savedProduct.color,
                size = savedProduct.sizeName(),
                topSize = (savedProduct as? BikiniSuit)?.topSize?.name,
                bottomSize = (savedProduct as? BikiniSuit)?.bottomSize?.name,
                unitPrice = unitPrice,
                quantity = savedItem.quantity,
                lineTotal = unitPrice.multiply(savedItem.quantity.toBigDecimal()),
                stock = savedProduct.warehouseVolume,
                productStatus = savedProduct.status.name,
                purchasable = savedProduct.status == Product.Status.ACTIVE &&
                    savedProduct.warehouseVolume >= savedItem.quantity,
                primaryImage = primaryImages[savedProductId],
                createdAt = savedItem.createdAt,
                updatedAt = savedItem.updatedAt,
            )
        }
        return ShoppingCartView(
            customerId = requireNotNull(savedCart.customer?.id),
            items = itemViews,
            totalQuantity = itemViews.sumOf(ShoppingCartItemView::quantity),
            subtotal = itemViews.fold(ZERO_AMOUNT) { total, savedItem -> total + savedItem.lineTotal },
            updatedAt = savedCart.updatedAt,
        )
    }

    @Transactional
    override fun clearCart(customerId: Long): ShoppingCartView {
        val cart = shoppingCartRepository.findByCustomerIdForUpdate(customerId) ?: return ShoppingCartView(
            customerId = customerId,
            items = emptyList(),
            totalQuantity = 0,
            subtotal = ZERO_AMOUNT,
            updatedAt = null,
        )
        cart.clear()
        cart.updatedAt = Instant.now()
        val savedCart = shoppingCartRepository.saveAndFlush(cart)
        return ShoppingCartView(
            customerId = requireNotNull(savedCart.customer?.id),
            items = emptyList(),
            totalQuantity = 0,
            subtotal = ZERO_AMOUNT,
            updatedAt = savedCart.updatedAt,
        )
    }

    private fun validateQuantity(quantity: Int) {
        if (quantity !in 1..MAX_ITEM_QUANTITY) {
            throw ParamErrorException("商品数量必须在 1 到 $MAX_ITEM_QUANTITY 之间")
        }
    }

    private fun Product.productType(): String = when (this) {
        is BikiniSuit -> "BIKINI"
        is OnePieceSuit -> "ONE_PIECE"
        is Dress -> "DRESS"
        is CoverUp -> "COVER_UP"
        else -> this::class.simpleName ?: "PRODUCT"
    }

    private fun Product.sizeName(): String? = when (this) {
        is OnePieceSuit -> size?.name
        is Dress -> size?.name
        is CoverUp -> size.name
        else -> null
    }

    private fun ensureStock(product: Product, quantity: Int) {
        if (product.warehouseVolume < quantity) {
            throw InsufficientStockException("商品 ${product.id} 库存不足，当前仅剩 ${product.warehouseVolume} 件")
        }
    }

    private fun findOrCreateLockedCart(customerId: Long): ShoppingCart {
        shoppingCartRepository.findByCustomerIdForUpdate(customerId)?.let { return it }
        val customer = userRepository.findByIdForUpdate(customerId)
            ?: throw ResourceNotFoundException("用户不存在")
        return shoppingCartRepository.findByCustomerIdForUpdate(customerId)
            ?: ShoppingCart(customer = customer)
    }

    private companion object {
        const val MAX_CART_LINES = 50
        const val MAX_ITEM_QUANTITY = 99
        val ZERO_AMOUNT: BigDecimal = BigDecimal("0.00")
    }
}
