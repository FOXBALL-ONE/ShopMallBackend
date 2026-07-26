package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.OrderLineCommand
import top.foxball.shopmall.service.OrderPageQuery
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.PlaceOrderCommand
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * @folder 订单
 */
@Validated
@RestController
class OrderController(
    private val orderService: OrderService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 创建订单
     * @param idempotencyKey 幂等键
     * @param productIds 商品 ID 列表
     * @param quantities 各商品对应的购买数量
     * @param addressId 配送地址 ID
     * @param clientMessage 客户留言
     */
    @PostMapping("/api/orders")
    fun placeOrder(
        @AuthenticationPrincipal userId: Long,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
        @RequestParam("product_ids") @Size(min = 1, max = 10) productIds: List<Long>,
        @RequestParam("quantities") @Size(min = 1, max = 10) quantities: List<Int>,
        @RequestParam("address_id") addressId: UUID,
        @RequestParam("client_message", required = false) @Size(max = 500) clientMessage: String?,
    ): ResponseEntity<Response> {
        if (productIds.size != quantities.size) {
            return builder.badRequest()
                .message("商品与数量必须一一对应")
                .build()
        }
        if (productIds.any { it < 1 } || quantities.any { it !in 1..99 }) {
            return builder.badRequest()
                .message("商品 ID 必须大于 0，数量必须在 1 到 99 之间")
                .build()
        }

        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("product_snapshot")
            val productSnapshot: String,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
        )

        data class AddressData(
            val name: String,
            val phone: String,
            val country: String,
            @param:JsonProperty("state_or_province")
            val stateOrProvince: String?,
            val city: String,
            val district: String?,
            @param:JsonProperty("postal_code")
            val postalCode: String?,
            val address1: String,
            val address2: String?,
            val company: String?,
            @param:JsonProperty("delivery_instructions")
            val deliveryInstructions: String?,
        )

        data class Response(
            val id: Long,
            @param:JsonProperty("order_no")
            val orderNo: String,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val status: String,
            @param:JsonProperty("items_subtotal")
            val itemsSubtotal: BigDecimal,
            @param:JsonProperty("shipping_fee")
            val shippingFee: BigDecimal,
            @param:JsonProperty("tax_amount")
            val taxAmount: BigDecimal,
            @param:JsonProperty("discount_amount")
            val discountAmount: BigDecimal,
            @param:JsonProperty("total_amount")
            val totalAmount: BigDecimal,
            val currency: String,
            @param:JsonProperty("payment_intent_id")
            val paymentIntentId: String?,
            @param:JsonProperty("client_secret")
            val clientSecret: String?,
            @param:JsonProperty("shipping_address")
            val shippingAddress: AddressData,
            @param:JsonProperty("client_message")
            val clientMessage: String?,
            @param:JsonProperty("expires_at")
            val expiresAt: Instant?,
            @param:JsonProperty("paid_at")
            val paidAt: Instant?,
            @param:JsonProperty("cancelled_at")
            val cancelledAt: Instant?,
            @param:JsonProperty("shipped_at")
            val shippedAt: Instant?,
            @param:JsonProperty("delivered_at")
            val deliveredAt: Instant?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
            val items: List<ItemData>,
        )

        val view = orderService.placeOrder(
            customerId = userId,
            command = PlaceOrderCommand(
                items = productIds.zip(quantities).map { (productId, quantity) ->
                    OrderLineCommand(productId, quantity)
                },
                addressId = addressId,
                clientMessage = clientMessage,
            ),
            idempotencyKey = idempotencyKey,
        )
        val order = view.order
        val address = order.shippingAddress
        val rs = Response(
            id = requireNotNull(order.id),
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = order.status.name,
            itemsSubtotal = order.itemsSubtotal,
            shippingFee = order.shippingFee,
            taxAmount = order.taxAmount,
            discountAmount = order.discountAmount,
            totalAmount = order.totalAmount,
            currency = order.currency,
            paymentIntentId = order.paymentIntentId,
            clientSecret = view.clientSecret,
            shippingAddress = AddressData(
                name = address.name,
                phone = address.phone,
                country = address.country,
                stateOrProvince = address.stateOrProvince,
                city = address.city,
                district = address.district,
                postalCode = address.postalCode,
                address1 = address.address1,
                address2 = address.address2,
                company = address.company,
                deliveryInstructions = address.deliveryInstructions,
            ),
            clientMessage = order.clientMessage,
            expiresAt = order.expiresAt,
            paidAt = order.paidAt,
            cancelledAt = order.cancelledAt,
            shippedAt = order.shippedAt,
            deliveredAt = order.deliveredAt,
            cancelReason = order.cancelReason,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
            items = view.items.map {
                ItemData(
                    id = requireNotNull(it.id),
                    productId = it.productId,
                    productSnapshot = it.productSnapshot,
                    unitPrice = it.unitPrice,
                    quantity = it.quantity,
                    lineTotal = it.lineTotal,
                    createdAt = it.createdAt,
                )
            },
        )
        return builder.status(HttpStatus.CREATED)
            .data(rs)
            .build()
    }

    /**
     * @api 获取我的订单列表
     * @param page 分页页码
     * @param pageSize 分页每页数量
     */
    @GetMapping("/api/orders")
    fun getCustomerOrders(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
    ): ResponseEntity<Response> {
        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("product_snapshot")
            val productSnapshot: String,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
        )

        data class AddressData(
            val name: String,
            val phone: String,
            val country: String,
            @param:JsonProperty("state_or_province")
            val stateOrProvince: String?,
            val city: String,
            val district: String?,
            @param:JsonProperty("postal_code")
            val postalCode: String?,
            val address1: String,
            val address2: String?,
            val company: String?,
            @param:JsonProperty("delivery_instructions")
            val deliveryInstructions: String?,
        )

        data class OrderData(
            val id: Long,
            @param:JsonProperty("order_no")
            val orderNo: String,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val status: String,
            @param:JsonProperty("items_subtotal")
            val itemsSubtotal: BigDecimal,
            @param:JsonProperty("shipping_fee")
            val shippingFee: BigDecimal,
            @param:JsonProperty("tax_amount")
            val taxAmount: BigDecimal,
            @param:JsonProperty("discount_amount")
            val discountAmount: BigDecimal,
            @param:JsonProperty("total_amount")
            val totalAmount: BigDecimal,
            val currency: String,
            @param:JsonProperty("payment_intent_id")
            val paymentIntentId: String?,
            @param:JsonProperty("client_secret")
            val clientSecret: String?,
            @param:JsonProperty("shipping_address")
            val shippingAddress: AddressData,
            @param:JsonProperty("client_message")
            val clientMessage: String?,
            @param:JsonProperty("expires_at")
            val expiresAt: Instant?,
            @param:JsonProperty("paid_at")
            val paidAt: Instant?,
            @param:JsonProperty("cancelled_at")
            val cancelledAt: Instant?,
            @param:JsonProperty("shipped_at")
            val shippedAt: Instant?,
            @param:JsonProperty("delivered_at")
            val deliveredAt: Instant?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
            val items: List<ItemData>,
        )

        data class Pagination(val count: Int)

        data class Response(
            val list: List<OrderData>,
            val pagination: Pagination,
        )

        val pagedData = orderService.listCustomer(userId, OrderPageQuery(page - 1, pageSize))
        val list = pagedData.content.map { view ->
            val order = view.order
            val address = order.shippingAddress
            OrderData(
                id = requireNotNull(order.id),
                orderNo = order.orderNo,
                customerId = order.customerId,
                status = order.status.name,
                itemsSubtotal = order.itemsSubtotal,
                shippingFee = order.shippingFee,
                taxAmount = order.taxAmount,
                discountAmount = order.discountAmount,
                totalAmount = order.totalAmount,
                currency = order.currency,
                paymentIntentId = order.paymentIntentId,
                clientSecret = view.clientSecret,
                shippingAddress = AddressData(
                    name = address.name,
                    phone = address.phone,
                    country = address.country,
                    stateOrProvince = address.stateOrProvince,
                    city = address.city,
                    district = address.district,
                    postalCode = address.postalCode,
                    address1 = address.address1,
                    address2 = address.address2,
                    company = address.company,
                    deliveryInstructions = address.deliveryInstructions,
                ),
                clientMessage = order.clientMessage,
                expiresAt = order.expiresAt,
                paidAt = order.paidAt,
                cancelledAt = order.cancelledAt,
                shippedAt = order.shippedAt,
                deliveredAt = order.deliveredAt,
                cancelReason = order.cancelReason,
                createdAt = order.createdAt,
                updatedAt = order.updatedAt,
                items = view.items.map {
                    ItemData(
                        id = requireNotNull(it.id),
                        productId = it.productId,
                        productSnapshot = it.productSnapshot,
                        unitPrice = it.unitPrice,
                        quantity = it.quantity,
                        lineTotal = it.lineTotal,
                        createdAt = it.createdAt,
                    )
                },
            )
        }
        val rs = Response(list, Pagination(pagedData.totalPages))
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 获取订单详情
     * @param orderNo 订单编号
     */
    @GetMapping("/api/orders/{orderNo}")
    fun getCustomerOrder(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("orderNo") orderNo: String,
    ): ResponseEntity<Response> {
        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("product_snapshot")
            val productSnapshot: String,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
        )

        data class AddressData(
            val name: String,
            val phone: String,
            val country: String,
            @param:JsonProperty("state_or_province")
            val stateOrProvince: String?,
            val city: String,
            val district: String?,
            @param:JsonProperty("postal_code")
            val postalCode: String?,
            val address1: String,
            val address2: String?,
            val company: String?,
            @param:JsonProperty("delivery_instructions")
            val deliveryInstructions: String?,
        )

        data class Response(
            val id: Long,
            @param:JsonProperty("order_no")
            val orderNo: String,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val status: String,
            @param:JsonProperty("items_subtotal")
            val itemsSubtotal: BigDecimal,
            @param:JsonProperty("shipping_fee")
            val shippingFee: BigDecimal,
            @param:JsonProperty("tax_amount")
            val taxAmount: BigDecimal,
            @param:JsonProperty("discount_amount")
            val discountAmount: BigDecimal,
            @param:JsonProperty("total_amount")
            val totalAmount: BigDecimal,
            val currency: String,
            @param:JsonProperty("payment_intent_id")
            val paymentIntentId: String?,
            @param:JsonProperty("client_secret")
            val clientSecret: String?,
            @param:JsonProperty("shipping_address")
            val shippingAddress: AddressData,
            @param:JsonProperty("client_message")
            val clientMessage: String?,
            @param:JsonProperty("expires_at")
            val expiresAt: Instant?,
            @param:JsonProperty("paid_at")
            val paidAt: Instant?,
            @param:JsonProperty("cancelled_at")
            val cancelledAt: Instant?,
            @param:JsonProperty("shipped_at")
            val shippedAt: Instant?,
            @param:JsonProperty("delivered_at")
            val deliveredAt: Instant?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
            val items: List<ItemData>,
        )

        val view = orderService.getCustomer(userId, orderNo)
        val order = view.order
        val address = order.shippingAddress
        val rs = Response(
            id = requireNotNull(order.id),
            orderNo = order.orderNo,
            customerId = order.customerId,
            status = order.status.name,
            itemsSubtotal = order.itemsSubtotal,
            shippingFee = order.shippingFee,
            taxAmount = order.taxAmount,
            discountAmount = order.discountAmount,
            totalAmount = order.totalAmount,
            currency = order.currency,
            paymentIntentId = order.paymentIntentId,
            clientSecret = view.clientSecret,
            shippingAddress = AddressData(
                name = address.name,
                phone = address.phone,
                country = address.country,
                stateOrProvince = address.stateOrProvince,
                city = address.city,
                district = address.district,
                postalCode = address.postalCode,
                address1 = address.address1,
                address2 = address.address2,
                company = address.company,
                deliveryInstructions = address.deliveryInstructions,
            ),
            clientMessage = order.clientMessage,
            expiresAt = order.expiresAt,
            paidAt = order.paidAt,
            cancelledAt = order.cancelledAt,
            shippedAt = order.shippedAt,
            deliveredAt = order.deliveredAt,
            cancelReason = order.cancelReason,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
            items = view.items.map {
                ItemData(
                    id = requireNotNull(it.id),
                    productId = it.productId,
                    productSnapshot = it.productSnapshot,
                    unitPrice = it.unitPrice,
                    quantity = it.quantity,
                    lineTotal = it.lineTotal,
                    createdAt = it.createdAt,
                )
            },
        )
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 获取订单支付信息
     * @param orderNo 订单编号
     */
    @GetMapping("/api/orders/{orderNo}/payment")
    fun getPayment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("orderNo") orderNo: String,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("order_no")
            val orderNo: String,
            val status: String,
            @param:JsonProperty("client_secret")
            val clientSecret: String?,
            @param:JsonProperty("expires_at")
            val expiresAt: Instant?,
        )

        val payment = orderService.getPayment(userId, orderNo)
        val rs = Response(
            orderNo = payment.orderNo,
            status = payment.status.name,
            clientSecret = payment.clientSecret,
            expiresAt = payment.expiresAt,
        )
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 取消订单
     * @param orderNo 订单编号
     * @param reason 取消原因
     */
    @PostMapping("/api/orders/{orderNo}/cancel")
    fun cancelOrder(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("orderNo") orderNo: String,
        @RequestParam("reason") @NotBlank @Size(max = 200) reason: String,
    ): ResponseEntity<Response> {
        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id")
            val productId: Long,
            @param:JsonProperty("product_snapshot")
            val productSnapshot: String,
            @param:JsonProperty("unit_price")
            val unitPrice: BigDecimal,
            val quantity: Int,
            @param:JsonProperty("line_total")
            val lineTotal: BigDecimal,
        )

        data class Response(
            val id: Long,
            @param:JsonProperty("order_no")
            val orderNo: String,
            val status: String,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            val items: List<ItemData>,
        )

        val view = orderService.cancel(userId, orderNo, reason)
        val order = view.order
        val rs = Response(
            id = requireNotNull(order.id),
            orderNo = order.orderNo,
            status = order.status.name,
            cancelReason = order.cancelReason,
            items = view.items.map {
                ItemData(
                    id = requireNotNull(it.id),
                    productId = it.productId,
                    productSnapshot = it.productSnapshot,
                    unitPrice = it.unitPrice,
                    quantity = it.quantity,
                    lineTotal = it.lineTotal,
                )
            },
        )
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 获取管理端订单列表
     * @param page 分页页码
     * @param pageSize 分页每页数量
     * @param status 订单状态
     * @param customerId 客户 ID
     * @param orderNo 订单编号
     */
    @GetMapping("/api/admin/orders")
    fun getAdminOrders(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
        @RequestParam("status", required = false) status: OrderStatus?,
        @RequestParam("customer_id", required = false) @Min(1) customerId: Long?,
        @RequestParam("order_no", required = false) @Size(max = 32) orderNo: String?,
    ): ResponseEntity<Response> {
        data class OrderData(
            val id: Long,
            @param:JsonProperty("order_no")
            val orderNo: String,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            val status: String,
            @param:JsonProperty("total_amount")
            val totalAmount: BigDecimal,
            val currency: String,
            @param:JsonProperty("created_at")
            val createdAt: Instant?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
        )

        data class Pagination(val count: Int)

        data class Response(
            val list: List<OrderData>,
            val pagination: Pagination,
        )

        val pagedData = orderService.listAdmin(
            adminId,
            AdminOrderQuery(page - 1, pageSize, status, customerId, orderNo),
        )
        val list = pagedData.content.map { view ->
            val order = view.order
            OrderData(
                id = requireNotNull(order.id),
                orderNo = order.orderNo,
                customerId = order.customerId,
                status = order.status.name,
                totalAmount = order.totalAmount,
                currency = order.currency,
                createdAt = order.createdAt,
                updatedAt = order.updatedAt,
            )
        }
        val rs = Response(list, Pagination(pagedData.totalPages))
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 退款订单
     * @param orderNo 订单编号
     * @param reason 退款原因
     */
    @PostMapping("/api/admin/orders/{orderNo}/refund")
    fun refundOrder(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("orderNo") orderNo: String,
        @RequestParam("reason") @NotBlank @Size(max = 200) reason: String,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("order_no")
            val orderNo: String,
            val status: String,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("updated_at")
            val updatedAt: Instant?,
        )

        val view = orderService.refund(adminId, orderNo, reason)
        val order = view.order
        val rs = Response(
            id = requireNotNull(order.id),
            orderNo = order.orderNo,
            status = order.status.name,
            cancelReason = order.cancelReason,
            updatedAt = order.updatedAt,
        )
        return builder.ok()
            .data(rs)
            .build()
    }
}
