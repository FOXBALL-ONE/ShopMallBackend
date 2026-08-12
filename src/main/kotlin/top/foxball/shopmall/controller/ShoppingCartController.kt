package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.service.ShoppingCartService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 购物车
 */
@Validated
@RestController
class ShoppingCartController(
    private val shoppingCartService: ShoppingCartService,
    private val builder: ResponseBuilder,
) {
    /** @api 获取我的购物车 */
    @GetMapping("/api/cart")
    fun getMyCart(
        @AuthenticationPrincipal userId: Long,
    ): ResponseEntity<Response> {
        
        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("variant_id")
            val variantId: Long,
            val sku: String,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val size: String?,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            val stock: Int,
            @param:JsonProperty("product_status")
            val productStatus: String,
            @param:JsonProperty("variant_status")
            val variantStatus: String,
            val purchasable: Boolean,
            @param:JsonProperty("primary_image")
            val primaryImage: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val items: List<ItemData>,
            @param:JsonProperty("total_quantity")
            val totalQuantity: Int,
            val subtotal: BigDecimal,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val cart = shoppingCartService.getCart(userId)
        val list = cart.items.map { item ->
            ItemData(
                id = item.id,
                productId = item.productId,
                variantId = item.variantId,
                sku = item.sku,
                productType = item.productType,
                name = item.name,
                color = item.color,
                size = item.size,
                topSize = item.topSize,
                bottomSize = item.bottomSize,
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                lineTotal = item.lineTotal,
                stock = item.stock,
                productStatus = item.productStatus,
                variantStatus = item.variantStatus,
                purchasable = item.purchasable,
                primaryImage = item.primaryImage,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
            )
        }
        val rs = Response(cart.customerId, list, cart.totalQuantity, cart.subtotal, cart.updatedAt)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 添加商品到购物车
     * @param variantId SKU ID
     * @param quantity 增加的商品数量
     */
    @PostMapping("/api/cart/items")
    fun addItem(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("variant_id") @Min(1) variantId: Long,
        @RequestParam("quantity", defaultValue = "1") @Min(1) @Max(99) quantity: Int,
    ): ResponseEntity<Response> {
        if (variantId < 1 || quantity !in 1..99) {
            return builder.badRequest().message("SKU ID 必须大于 0，数量必须在 1 到 99 之间").build()
        }

        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("variant_id")
            val variantId: Long,
            val sku: String,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val size: String?,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            val stock: Int,
            @param:JsonProperty("product_status")
            val productStatus: String,
            @param:JsonProperty("variant_status")
            val variantStatus: String,
            val purchasable: Boolean,
            @param:JsonProperty("primary_image")
            val primaryImage: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val items: List<ItemData>,
            @param:JsonProperty("total_quantity")
            val totalQuantity: Int,
            val subtotal: BigDecimal,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val cart = shoppingCartService.addItem(userId, variantId, quantity)
        val list = cart.items.map { item ->
            ItemData(
                id = item.id,
                productId = item.productId,
                variantId = item.variantId,
                sku = item.sku,
                productType = item.productType,
                name = item.name,
                color = item.color,
                size = item.size,
                topSize = item.topSize,
                bottomSize = item.bottomSize,
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                lineTotal = item.lineTotal,
                stock = item.stock,
                productStatus = item.productStatus,
                variantStatus = item.variantStatus,
                purchasable = item.purchasable,
                primaryImage = item.primaryImage,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
            )
        }
        val rs = Response(cart.customerId, list, cart.totalQuantity, cart.subtotal, cart.updatedAt)
        return builder.created().data(rs).build()
    }

    /**
     * @api 修改购物车商品数量
     * @param itemId 购物车明细 ID
     * @param quantity 修改后的绝对数量
     */
    @PutMapping("/api/cart/items/{itemId}")
    fun updateItem(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("itemId") @Min(1) itemId: Long,
        @RequestParam("quantity") @Min(1) @Max(99) quantity: Int,
    ): ResponseEntity<Response> {
        if (itemId < 1 || quantity !in 1..99) {
            return builder.badRequest().message("购物车商品 ID 必须大于 0，数量必须在 1 到 99 之间").build()
        }

        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("variant_id")
            val variantId: Long,
            val sku: String,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val size: String?,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            val stock: Int,
            @param:JsonProperty("product_status")
            val productStatus: String,
            @param:JsonProperty("variant_status")
            val variantStatus: String,
            val purchasable: Boolean,
            @param:JsonProperty("primary_image")
            val primaryImage: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val items: List<ItemData>,
            @param:JsonProperty("total_quantity")
            val totalQuantity: Int,
            val subtotal: BigDecimal,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val cart = shoppingCartService.updateItem(userId, itemId, quantity)
            ?: return builder.notFound().message("购物车商品不存在").build()
        val list = cart.items.map { item ->
            ItemData(
                id = item.id,
                productId = item.productId,
                variantId = item.variantId,
                sku = item.sku,
                productType = item.productType,
                name = item.name,
                color = item.color,
                size = item.size,
                topSize = item.topSize,
                bottomSize = item.bottomSize,
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                lineTotal = item.lineTotal,
                stock = item.stock,
                productStatus = item.productStatus,
                variantStatus = item.variantStatus,
                purchasable = item.purchasable,
                primaryImage = item.primaryImage,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
            )
        }
        val rs = Response(cart.customerId, list, cart.totalQuantity, cart.subtotal, cart.updatedAt)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除购物车商品
     * @param itemId 购物车明细 ID
     */
    @DeleteMapping("/api/cart/items/{itemId}")
    fun removeItem(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("itemId") @Min(1) itemId: Long,
    ): ResponseEntity<Response> {
        if (itemId < 1) {
            return builder.badRequest().message("购物车商品 ID 必须大于 0").build()
        }

        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("variant_id")
            val variantId: Long,
            val sku: String,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val size: String?,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            val stock: Int,
            @param:JsonProperty("product_status")
            val productStatus: String,
            @param:JsonProperty("variant_status")
            val variantStatus: String,
            val purchasable: Boolean,
            @param:JsonProperty("primary_image")
            val primaryImage: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val items: List<ItemData>,
            @param:JsonProperty("total_quantity")
            val totalQuantity: Int,
            val subtotal: BigDecimal,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val cart = shoppingCartService.removeItem(userId, itemId)
            ?: return builder.notFound().message("购物车商品不存在").build()
        val list = cart.items.map { item ->
            ItemData(
                id = item.id,
                productId = item.productId,
                variantId = item.variantId,
                sku = item.sku,
                productType = item.productType,
                name = item.name,
                color = item.color,
                size = item.size,
                topSize = item.topSize,
                bottomSize = item.bottomSize,
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                lineTotal = item.lineTotal,
                stock = item.stock,
                productStatus = item.productStatus,
                variantStatus = item.variantStatus,
                purchasable = item.purchasable,
                primaryImage = item.primaryImage,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
            )
        }
        val rs = Response(cart.customerId, list, cart.totalQuantity, cart.subtotal, cart.updatedAt)
        return builder.ok().data(rs).build()
    }

    /** @api 清空我的购物车 */
    @DeleteMapping("/api/cart")
    fun clearMyCart(
        @AuthenticationPrincipal userId: Long,
    ): ResponseEntity<Response> {
        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("variant_id")
            val variantId: Long,
            val sku: String,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val size: String?,
            @param:JsonProperty("top_size")
            val topSize: String?,
            @param:JsonProperty("bottom_size")
            val bottomSize: String?,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            val stock: Int,
            @param:JsonProperty("product_status")
            val productStatus: String,
            @param:JsonProperty("variant_status")
            val variantStatus: String,
            val purchasable: Boolean,
            @param:JsonProperty("primary_image")
            val primaryImage: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val items: List<ItemData>,
            @param:JsonProperty("total_quantity")
            val totalQuantity: Int,
            val subtotal: BigDecimal,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val cart = shoppingCartService.clearCart(userId)
        val list = cart.items.map { item ->
            ItemData(
                id = item.id,
                productId = item.productId,
                variantId = item.variantId,
                sku = item.sku,
                productType = item.productType,
                name = item.name,
                color = item.color,
                size = item.size,
                topSize = item.topSize,
                bottomSize = item.bottomSize,
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                lineTotal = item.lineTotal,
                stock = item.stock,
                productStatus = item.productStatus,
                variantStatus = item.variantStatus,
                purchasable = item.purchasable,
                primaryImage = item.primaryImage,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
            )
        }
        val rs = Response(cart.customerId, list, cart.totalQuantity, cart.subtotal, cart.updatedAt)
        return builder.ok().data(rs).build()
    }
}
