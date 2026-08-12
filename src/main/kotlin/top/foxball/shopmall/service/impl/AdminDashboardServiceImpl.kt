package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.AdminDashboardReportRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminDashboardService
import top.foxball.shopmall.service.AdminDashboardSummary
import top.foxball.shopmall.service.AdminDailyOperations
import top.foxball.shopmall.service.AdminOperationsPeriod
import top.foxball.shopmall.service.AdminOperationsReport
import top.foxball.shopmall.service.AdminRevenueAmount
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

@Service
@Transactional(readOnly = true)
class AdminDashboardServiceImpl(
    private val orderRepository: OrderRepository,
    private val shipmentRepository: ShipmentRepository,
    private val supportTicketRepository: SupportTicketRepository,
    private val productRepository: ProductRepository,
    private val variantRepository: ProductVariantRepository,
    private val reportRepository: AdminDashboardReportRepository,
    private val adminAccessService: AdminAccessService,
) : AdminDashboardService {
    override fun summary(adminId: Long, lowStockThreshold: Int): AdminDashboardSummary {
        adminAccessService.requireAdmin(adminId)
        return AdminDashboardSummary(
            pendingPaymentOrders = orderRepository.countByStatus(OrderStatus.PENDING_PAYMENT),
            paidOrders = orderRepository.countByStatus(OrderStatus.PAID),
            shippedOrders = orderRepository.countByStatus(OrderStatus.SHIPPED),
            deliveredOrders = orderRepository.countByStatus(OrderStatus.DELIVERED),
            completedOrders = orderRepository.countByStatus(OrderStatus.COMPLETED),
            cancelledOrders = orderRepository.countByStatus(OrderStatus.CANCELLED),
            labelPendingShipments = shipmentRepository.countByStatus(ShipmentStatus.LABEL_PENDING),
            labelCreatedShipments = shipmentRepository.countByStatus(ShipmentStatus.LABEL_CREATED),
            cancelPendingShipments = shipmentRepository.countByStatus(ShipmentStatus.CANCEL_PENDING),
            inTransitShipments = shipmentRepository.countByStatus(ShipmentStatus.IN_TRANSIT),
            outForDeliveryShipments = shipmentRepository.countByStatus(ShipmentStatus.OUT_FOR_DELIVERY),
            deliveredShipments = shipmentRepository.countByStatus(ShipmentStatus.DELIVERED),
            cancelledShipments = shipmentRepository.countByStatus(ShipmentStatus.CANCELLED),
            shipmentErrors = shipmentRepository.countByLastTrackErrorIsNotNull(),
            openTickets = supportTicketRepository.countByStatus(SupportTicketStatus.OPEN),
            inProgressTickets = supportTicketRepository.countByStatus(SupportTicketStatus.IN_PROGRESS),
            highPriorityTickets = supportTicketRepository.countByPriorityAndStatusIn(
                SupportTicketPriority.HIGH,
                listOf(SupportTicketStatus.OPEN, SupportTicketStatus.IN_PROGRESS),
            ),
            activeProducts = productRepository.countByStatusAndDeletedAtIsNull(Product.Status.ACTIVE),
            inactiveProducts = productRepository.countByStatusAndDeletedAtIsNull(Product.Status.INACTIVE),
            deletedProducts = productRepository.countByDeletedAtIsNotNull(),
            lowStockProducts = variantRepository.countByStatusAndWarehouseVolumeLessThanEqual(
                ProductVariant.Status.ACTIVE,
                lowStockThreshold,
            ),
        )
    }

    override fun operations(adminId: Long, days: Int): AdminOperationsReport {
        adminAccessService.requireAdmin(adminId)

        val today = LocalDate.now(ZoneOffset.UTC)
        val currentStart = today.minusDays(days.toLong() - 1)
        val previousStart = currentStart.minusDays(days.toLong())
        val until = today.plusDays(1)
        val queryFrom = previousStart.atStartOfDay()
        val queryUntil = until.atStartOfDay()
        val orderCounts = reportRepository.findDailyOrderCounts(queryFrom, queryUntil)
        val revenues = reportRepository.findDailyRevenue(queryFrom, queryUntil)
        val customerCounts = reportRepository.findDailyCustomerCounts(queryFrom, queryUntil)
        val orderCountsByDate = orderCounts.associateBy { it.date }
        val revenuesByDate = revenues.groupBy { it.date }
        val customerCountsByDate = customerCounts.associateBy { it.date }

        fun period(from: LocalDate, to: LocalDate): AdminOperationsPeriod {
            val periodOrderCounts = orderCounts.filter { !it.date.isBefore(from) && it.date.isBefore(to) }
            val revenueTotals = sortedMapOf<String, BigDecimal>()
            revenues
                .filter { !it.date.isBefore(from) && it.date.isBefore(to) }
                .forEach { revenue ->
                    revenueTotals.merge(revenue.currency, revenue.amount, BigDecimal::add)
                }
            return AdminOperationsPeriod(
                orders = periodOrderCounts.sumOf { it.orders },
                paidOrders = periodOrderCounts.sumOf { it.paidOrders },
                newCustomers = customerCounts
                    .filter { !it.date.isBefore(from) && it.date.isBefore(to) }
                    .sumOf { it.customers },
                revenue = revenueTotals.map { (currency, amount) ->
                    AdminRevenueAmount(currency = currency, amount = amount)
                },
            )
        }

        return AdminOperationsReport(
            periodDays = days,
            currentPeriod = period(currentStart, until),
            previousPeriod = period(previousStart, currentStart),
            daily = (0 until days).map { offset ->
                val date = currentStart.plusDays(offset.toLong())
                val counts = orderCountsByDate[date]
                AdminDailyOperations(
                    date = date,
                    orders = counts?.orders ?: 0,
                    paidOrders = counts?.paidOrders ?: 0,
                    newCustomers = customerCountsByDate[date]?.customers ?: 0,
                    revenue = revenuesByDate[date].orEmpty().map { revenue ->
                        AdminRevenueAmount(currency = revenue.currency, amount = revenue.amount)
                    },
                )
            },
        )
    }
}
