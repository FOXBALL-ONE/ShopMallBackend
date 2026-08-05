package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.service.AdminDashboardService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder

/**
 * @folder 管理端/仪表盘
 */
@Validated
@RestController
@RequestMapping("/admin/api/dashboard")
class AdminDashboardController(
    private val dashboardService: AdminDashboardService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取仪表盘汇总
     * @param lowStockThreshold 低库存阈值
     */
    @GetMapping("/summary")
    fun getSummary(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("low_stock_threshold", defaultValue = "10") @Min(0) @Max(10000) lowStockThreshold: Int,
    ): ResponseEntity<Response> {
        data class OrderData(
            @param:JsonProperty("pending_payment") val pendingPayment: Long,
            val paid: Long,
            val shipped: Long,
            val delivered: Long,
            val completed: Long,
            val cancelled: Long,
        )

        data class ShipmentData(
            @param:JsonProperty("label_pending") val labelPending: Long,
            @param:JsonProperty("label_created") val labelCreated: Long,
            @param:JsonProperty("cancel_pending") val cancelPending: Long,
            @param:JsonProperty("in_transit") val inTransit: Long,
            @param:JsonProperty("out_for_delivery") val outForDelivery: Long,
            val delivered: Long,
            val cancelled: Long,
            val errors: Long,
        )

        data class TicketData(
            val open: Long,
            @param:JsonProperty("in_progress") val inProgress: Long,
            @param:JsonProperty("high_priority") val highPriority: Long,
        )

        data class ProductData(
            val active: Long,
            val inactive: Long,
            val deleted: Long,
            @param:JsonProperty("low_stock") val lowStock: Long,
        )

        data class Response(
            val orders: OrderData,
            val shipments: ShipmentData,
            val tickets: TicketData,
            val products: ProductData,
        )

        val summary = dashboardService.summary(adminId, lowStockThreshold)
        val rs = Response(
            orders = OrderData(
                pendingPayment = summary.pendingPaymentOrders,
                paid = summary.paidOrders,
                shipped = summary.shippedOrders,
                delivered = summary.deliveredOrders,
                completed = summary.completedOrders,
                cancelled = summary.cancelledOrders,
            ),
            shipments = ShipmentData(
                labelPending = summary.labelPendingShipments,
                labelCreated = summary.labelCreatedShipments,
                cancelPending = summary.cancelPendingShipments,
                inTransit = summary.inTransitShipments,
                outForDelivery = summary.outForDeliveryShipments,
                delivered = summary.deliveredShipments,
                cancelled = summary.cancelledShipments,
                errors = summary.shipmentErrors,
            ),
            tickets = TicketData(
                open = summary.openTickets,
                inProgress = summary.inProgressTickets,
                highPriority = summary.highPriorityTickets,
            ),
            products = ProductData(
                active = summary.activeProducts,
                inactive = summary.inactiveProducts,
                deleted = summary.deletedProducts,
                lowStock = summary.lowStockProducts,
            ),
        )
        return builder.ok().data(rs).build()
    }
}
