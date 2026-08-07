package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.Instant

/**
 * @folder 管理端/订单
 */
@Validated
@RestController
@RequestMapping("/admin/api/orders")
class AdminOrderController(
    private val orderService: OrderService,
    private val userService: UserService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取管理端订单列表
     * @param page 分页页码
     * @param pageSize 分页每页数量
     * @param status 订单状态
     * @param customerId 客户 ID
     * @param customerUsername 客户用户名
     * @param orderNo 订单编号
     */
    @GetMapping
    fun getAdminOrders(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
        @RequestParam("status", required = false) status: OrderStatus?,
        @RequestParam("customer_id", required = false) @Min(1) customerId: Long?,
        @RequestParam("customer_username", required = false) @Size(max = 50) customerUsername: String?,
        @RequestParam("order_no", required = false) @Size(max = 32) orderNo: String?,
    ): ResponseEntity<Response> {
        data class OrderData(
            val id: Long,
            @param:JsonProperty("order_no")
            val orderNo: String,
            @param:JsonProperty("customer_id")
            val customerId: Long,
            @param:JsonProperty("customer_username")
            val customerUsername: String,
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

        val normalizedCustomerUsername = customerUsername?.trim()?.takeIf(String::isNotEmpty)
        val resolvedCustomerId = normalizedCustomerUsername?.let {
            userService.getUserByUsername(it)?.id ?: 0L
        } ?: customerId
        val pagedData = orderService.listAdmin(
            adminId,
            AdminOrderQuery(page - 1, pageSize, status, resolvedCustomerId, orderNo),
        )
        val customerUsernames = userService.getUsernamesByIds(
            pagedData.content.map { it.order.customerId }.distinct(),
        )
        val list = pagedData.content.map { view ->
            val order = view.order
            OrderData(
                id = requireNotNull(order.id),
                orderNo = order.orderNo,
                customerId = order.customerId,
                customerUsername = requireNotNull(customerUsernames[order.customerId]) { "订单客户不存在" },
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
     * @api 获取管理端订单详情
     * @param orderNo 订单编号
     */
    @GetMapping("/{order_no}")
    fun getAdminOrder(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("order_no") orderNo: String,
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
            @param:JsonProperty("allocated_quantity")
            val allocatedQuantity: Int,
            @param:JsonProperty("remaining_quantity")
            val remainingQuantity: Int,
            val allocated: Boolean,
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
            @param:JsonProperty("customer_username")
            val customerUsername: String,
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

        val details = orderService.getAdmin(adminId, orderNo)
        val order = details.order
        val customerUsername = requireNotNull(userService.getUsernameById(order.customerId)) { "订单客户不存在" }
        val address = order.shippingAddress
        val items = details.items.map { item ->
            val itemId = requireNotNull(item.id)
            val allocatedQuantity = minOf(details.allocatedQuantities[itemId] ?: 0, item.quantity)
            ItemData(
                id = itemId,
                productId = item.productId,
                productSnapshot = item.productSnapshot,
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                lineTotal = item.lineTotal,
                allocatedQuantity = allocatedQuantity,
                remainingQuantity = item.quantity - allocatedQuantity,
                allocated = allocatedQuantity > 0,
                createdAt = item.createdAt,
            )
        }
        val rs = Response(
            id = requireNotNull(order.id),
            orderNo = order.orderNo,
            customerId = order.customerId,
            customerUsername = customerUsername,
            status = order.status.name,
            itemsSubtotal = order.itemsSubtotal,
            shippingFee = order.shippingFee,
            taxAmount = order.taxAmount,
            discountAmount = order.discountAmount,
            totalAmount = order.totalAmount,
            currency = order.currency,
            paymentIntentId = order.paymentIntentId,
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
            items = items,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 退款订单
     * @param orderNo 订单编号
     * @param reason 退款原因
     */
    @PostMapping("/{order_no}/refund")
    fun refundOrder(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("order_no") orderNo: String,
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

    /**
     * @api 逻辑删除订单
     * @param orderNo 订单编号
     */
    @DeleteMapping("/{order_no}")
    fun deleteOrder(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("order_no") orderNo: String,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("order_no")
            val orderNo: String,
            val status: String,
        )

        val deleted = orderService.delete(adminId, orderNo)
        val rs = Response(
            orderNo = deleted.orderNo,
            status = deleted.status.name,
        )
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 永久删除订单
     * @param orderNo 订单编号
     */
    @DeleteMapping("/{order_no}/permanent")
    fun permanentlyDeleteOrder(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("order_no") orderNo: String,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("order_no")
            val orderNo: String,
            @param:JsonProperty("physically_deleted")
            val physicallyDeleted: Boolean,
        )

        orderService.permanentlyDelete(adminId, orderNo)
        val rs = Response(
            orderNo = orderNo,
            physicallyDeleted = true,
        )
        return builder.ok()
            .data(rs)
            .build()
    }
}
