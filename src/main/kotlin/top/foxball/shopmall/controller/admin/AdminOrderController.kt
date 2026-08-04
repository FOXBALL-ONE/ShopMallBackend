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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.OrderService
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
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取管理端订单列表
     * @param page 分页页码
     * @param pageSize 分页每页数量
     * @param status 订单状态
     * @param customerId 客户 ID
     * @param orderNo 订单编号
     */
    @GetMapping
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
}
