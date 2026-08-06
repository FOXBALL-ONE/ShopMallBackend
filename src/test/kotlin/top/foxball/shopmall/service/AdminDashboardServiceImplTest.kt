package top.foxball.shopmall.service

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.repository.AdminDailyCustomerCount
import top.foxball.shopmall.repository.AdminDailyOrderCount
import top.foxball.shopmall.repository.AdminDailyRevenue
import top.foxball.shopmall.repository.AdminDashboardReportRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.service.impl.AdminDashboardServiceImpl
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AdminDashboardServiceImplTest {
    private val orderRepository = mock(OrderRepository::class.java)
    private val shipmentRepository = mock(ShipmentRepository::class.java)
    private val supportTicketRepository = mock(SupportTicketRepository::class.java)
    private val productRepository = mock(ProductRepository::class.java)
    private val reportRepository = mock(AdminDashboardReportRepository::class.java)
    private val adminAccessService = mock(AdminAccessService::class.java)
    private val service = AdminDashboardServiceImpl(
        orderRepository,
        shipmentRepository,
        supportTicketRepository,
        productRepository,
        reportRepository,
        adminAccessService,
    )

    @Test
    fun `operations compares equal periods and fills missing dates`() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val currentStart = today.minusDays(6)
        val previousStart = currentStart.minusDays(7)
        val until = today.plusDays(1)
        val queryFrom = previousStart.atStartOfDay()
        val queryUntil = until.atStartOfDay()

        `when`(reportRepository.findDailyOrderCounts(queryFrom, queryUntil)).thenReturn(
            listOf(
                AdminDailyOrderCount(previousStart, orders = 2, paidOrders = 1),
                AdminDailyOrderCount(currentStart, orders = 4, paidOrders = 3),
                AdminDailyOrderCount(today, orders = 6, paidOrders = 5),
            ),
        )
        `when`(reportRepository.findDailyRevenue(queryFrom, queryUntil)).thenReturn(
            listOf(
                AdminDailyRevenue(previousStart, "USD", BigDecimal("10.00")),
                AdminDailyRevenue(currentStart, "USD", BigDecimal("20.00")),
                AdminDailyRevenue(today, "EUR", BigDecimal("5.00")),
                AdminDailyRevenue(today, "USD", BigDecimal("30.00")),
            ),
        )
        `when`(reportRepository.findDailyCustomerCounts(queryFrom, queryUntil)).thenReturn(
            listOf(
                AdminDailyCustomerCount(previousStart, customers = 1),
                AdminDailyCustomerCount(currentStart, customers = 2),
                AdminDailyCustomerCount(today, customers = 1),
            ),
        )

        val report = service.operations(adminId = 99, days = 7)

        assertEquals(7, report.periodDays)
        assertEquals(10, report.currentPeriod.orders)
        assertEquals(8, report.currentPeriod.paidOrders)
        assertEquals(3, report.currentPeriod.newCustomers)
        assertEquals(
            listOf(
                AdminRevenueAmount("EUR", BigDecimal("5.00")),
                AdminRevenueAmount("USD", BigDecimal("50.00")),
            ),
            report.currentPeriod.revenue,
        )
        assertEquals(2, report.previousPeriod.orders)
        assertEquals(7, report.daily.size)
        assertEquals(currentStart, report.daily.first().date)
        assertEquals(4, report.daily.first().orders)
        assertEquals(0, report.daily[1].orders)
        assertEquals(today, report.daily.last().date)
        assertEquals(6, report.daily.last().orders)

        verify(adminAccessService).requireAdmin(99)
        verify(reportRepository).findDailyOrderCounts(queryFrom, queryUntil)
        verify(reportRepository).findDailyRevenue(queryFrom, queryUntil)
        verify(reportRepository).findDailyCustomerCounts(queryFrom, queryUntil)
    }
}
