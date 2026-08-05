package top.foxball.shopmall.service

data class AdminDashboardSummary(
    val pendingPaymentOrders: Long,
    val paidOrders: Long,
    val shippedOrders: Long,
    val deliveredOrders: Long,
    val completedOrders: Long,
    val cancelledOrders: Long,
    val labelPendingShipments: Long,
    val labelCreatedShipments: Long,
    val cancelPendingShipments: Long,
    val inTransitShipments: Long,
    val outForDeliveryShipments: Long,
    val deliveredShipments: Long,
    val cancelledShipments: Long,
    val shipmentErrors: Long,
    val openTickets: Long,
    val inProgressTickets: Long,
    val highPriorityTickets: Long,
    val activeProducts: Long,
    val inactiveProducts: Long,
    val deletedProducts: Long,
    val lowStockProducts: Long,
)

interface AdminDashboardService {
    fun summary(adminId: Long, lowStockThreshold: Int): AdminDashboardSummary
}
