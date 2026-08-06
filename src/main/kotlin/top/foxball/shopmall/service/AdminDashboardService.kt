package top.foxball.shopmall.service

import java.math.BigDecimal
import java.time.LocalDate

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

data class AdminRevenueAmount(
    val currency: String,
    val amount: BigDecimal,
)

data class AdminOperationsPeriod(
    val orders: Long,
    val paidOrders: Long,
    val newCustomers: Long,
    val revenue: List<AdminRevenueAmount>,
)

data class AdminDailyOperations(
    val date: LocalDate,
    val orders: Long,
    val paidOrders: Long,
    val newCustomers: Long,
    val revenue: List<AdminRevenueAmount>,
)

data class AdminOperationsReport(
    val periodDays: Int,
    val currentPeriod: AdminOperationsPeriod,
    val previousPeriod: AdminOperationsPeriod,
    val daily: List<AdminDailyOperations>,
)

interface AdminDashboardService {
    fun summary(adminId: Long, lowStockThreshold: Int): AdminDashboardSummary

    fun operations(adminId: Long, days: Int): AdminOperationsReport
}
