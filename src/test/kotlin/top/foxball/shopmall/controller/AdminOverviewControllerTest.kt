package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.controller.admin.AdminDashboardController
import top.foxball.shopmall.controller.admin.AdminSessionController
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminDashboardService
import top.foxball.shopmall.service.AdminDashboardSummary
import top.foxball.shopmall.service.AdminDailyOperations
import top.foxball.shopmall.service.AdminOperationsPeriod
import top.foxball.shopmall.service.AdminOperationsReport
import top.foxball.shopmall.service.AdminRevenueAmount
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDate

class AdminOverviewControllerTest {
    private lateinit var userService: UserService
    private lateinit var adminAccessService: AdminAccessService
    private lateinit var dashboardService: AdminDashboardService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        userService = mock(UserService::class.java)
        adminAccessService = mock(AdminAccessService::class.java)
        dashboardService = mock(AdminDashboardService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminSessionController(userService, adminAccessService, ResponseBuilder()),
            AdminDashboardController(dashboardService, ResponseBuilder()),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(99L, null)
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `session returns current administrator profile with snake case names`() {
        `when`(userService.getUserById(99L)).thenReturn(
            User(
                id = 99L,
                username = "operator",
                email = "operator@example.test",
                firstName = "Shop",
                lastName = "Admin",
                role = Role.ADMIN,
            ),
        )

        mockMvc.perform(get("/admin/api/session"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(99))
            .andExpect(jsonPath("$.data.first_name").value("Shop"))
            .andExpect(jsonPath("$.data.last_name").value("Admin"))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))

        verify(adminAccessService).requireAdmin(99L)
    }

    @Test
    fun `dashboard returns operational summary groups`() {
        `when`(dashboardService.summary(99L, 5)).thenReturn(
            AdminDashboardSummary(
                pendingPaymentOrders = 1,
                paidOrders = 2,
                shippedOrders = 3,
                deliveredOrders = 4,
                completedOrders = 5,
                cancelledOrders = 6,
                labelPendingShipments = 7,
                labelCreatedShipments = 8,
                cancelPendingShipments = 9,
                inTransitShipments = 10,
                outForDeliveryShipments = 11,
                deliveredShipments = 12,
                cancelledShipments = 13,
                shipmentErrors = 14,
                openTickets = 15,
                inProgressTickets = 16,
                highPriorityTickets = 17,
                activeProducts = 18,
                inactiveProducts = 19,
                deletedProducts = 20,
                lowStockProducts = 21,
            ),
        )

        mockMvc.perform(get("/admin/api/dashboard/summary").param("low_stock_threshold", "5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.orders.pending_payment").value(1))
            .andExpect(jsonPath("$.data.shipments.errors").value(14))
            .andExpect(jsonPath("$.data.tickets.high_priority").value(17))
            .andExpect(jsonPath("$.data.products.low_stock").value(21))

        verify(dashboardService).summary(99L, 5)
    }

    @Test
    fun `dashboard returns operational trend with period comparisons`() {
        `when`(dashboardService.operations(99L, 14)).thenReturn(
            AdminOperationsReport(
                periodDays = 14,
                currentPeriod = AdminOperationsPeriod(
                    orders = 40,
                    paidOrders = 32,
                    newCustomers = 9,
                    revenue = listOf(AdminRevenueAmount("USD", BigDecimal("1280.50"))),
                ),
                previousPeriod = AdminOperationsPeriod(
                    orders = 30,
                    paidOrders = 21,
                    newCustomers = 6,
                    revenue = listOf(AdminRevenueAmount("USD", BigDecimal("940.00"))),
                ),
                daily = listOf(
                    AdminDailyOperations(
                        date = LocalDate.of(2026, 8, 6),
                        orders = 4,
                        paidOrders = 3,
                        newCustomers = 2,
                        revenue = listOf(AdminRevenueAmount("USD", BigDecimal("120.50"))),
                    ),
                ),
            ),
        )

        mockMvc.perform(get("/admin/api/dashboard/operations").param("days", "14"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.period_days").value(14))
            .andExpect(jsonPath("$.data.current_period.paid_orders").value(32))
            .andExpect(jsonPath("$.data.current_period.revenue[0].amount").value(1280.50))
            .andExpect(jsonPath("$.data.daily[0].date").value("2026-08-06"))
            .andExpect(jsonPath("$.data.daily[0].new_customers").value(2))

        verify(dashboardService).operations(99L, 14)
    }

}
