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
import top.foxball.shopmall.service.AdminSystemStatusService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * @folder 管理端/仪表盘
 */
@Validated
@RestController
@RequestMapping("/admin/api/dashboard")
class AdminDashboardController(
    private val dashboardService: AdminDashboardService,
    private val systemStatusService: AdminSystemStatusService,
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

    /**
     * @api 获取运营趋势报表
     * @param days 统计周期天数
     */
    @GetMapping("/operations")
    fun getOperations(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("days", defaultValue = "14") @Min(7) @Max(90) days: Int,
    ): ResponseEntity<Response> {
        data class RevenueData(
            val currency: String,
            val amount: BigDecimal,
        )

        data class PeriodData(
            val orders: Long,
            @param:JsonProperty("paid_orders") val paidOrders: Long,
            @param:JsonProperty("new_customers") val newCustomers: Long,
            val revenue: List<RevenueData>,
        )

        data class DailyData(
            val date: LocalDate,
            val orders: Long,
            @param:JsonProperty("paid_orders") val paidOrders: Long,
            @param:JsonProperty("new_customers") val newCustomers: Long,
            val revenue: List<RevenueData>,
        )

        data class Response(
            @param:JsonProperty("period_days") val periodDays: Int,
            @param:JsonProperty("current_period") val currentPeriod: PeriodData,
            @param:JsonProperty("previous_period") val previousPeriod: PeriodData,
            val daily: List<DailyData>,
        )

        val report = dashboardService.operations(adminId, days)
        val rs = Response(
            periodDays = report.periodDays,
            currentPeriod = PeriodData(
                orders = report.currentPeriod.orders,
                paidOrders = report.currentPeriod.paidOrders,
                newCustomers = report.currentPeriod.newCustomers,
                revenue = report.currentPeriod.revenue.map { revenue ->
                    RevenueData(currency = revenue.currency, amount = revenue.amount)
                },
            ),
            previousPeriod = PeriodData(
                orders = report.previousPeriod.orders,
                paidOrders = report.previousPeriod.paidOrders,
                newCustomers = report.previousPeriod.newCustomers,
                revenue = report.previousPeriod.revenue.map { revenue ->
                    RevenueData(currency = revenue.currency, amount = revenue.amount)
                },
            ),
            daily = report.daily.map { daily ->
                DailyData(
                    date = daily.date,
                    orders = daily.orders,
                    paidOrders = daily.paidOrders,
                    newCustomers = daily.newCustomers,
                    revenue = daily.revenue.map { revenue ->
                        RevenueData(currency = revenue.currency, amount = revenue.amount)
                    },
                )
            },
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取后端系统运行状态
     */
    @GetMapping("/system-status")
    fun getSystemStatus(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class ApplicationData(
            val name: String,
            val version: String,
            @param:JsonProperty("started_at") val startedAt: Instant,
            @param:JsonProperty("uptime_seconds") val uptimeSeconds: Long,
            @param:JsonProperty("available_processors") val availableProcessors: Int,
            @param:JsonProperty("system_load_average") val systemLoadAverage: Double?,
            @param:JsonProperty("process_cpu_usage") val processCpuUsage: Double?,
            @param:JsonProperty("system_cpu_usage") val systemCpuUsage: Double?,
        )

        data class JvmData(
            @param:JsonProperty("heap_used_bytes") val heapUsedBytes: Long,
            @param:JsonProperty("heap_committed_bytes") val heapCommittedBytes: Long,
            @param:JsonProperty("heap_max_bytes") val heapMaxBytes: Long,
            @param:JsonProperty("non_heap_used_bytes") val nonHeapUsedBytes: Long,
            @param:JsonProperty("live_threads") val liveThreads: Int,
            @param:JsonProperty("peak_threads") val peakThreads: Int,
            @param:JsonProperty("daemon_threads") val daemonThreads: Int,
            @param:JsonProperty("gc_collection_count") val gcCollectionCount: Long,
            @param:JsonProperty("gc_collection_time_ms") val gcCollectionTimeMillis: Long,
        )

        data class DatabaseData(
            val available: Boolean,
            @param:JsonProperty("latency_ms") val latencyMillis: Long,
            @param:JsonProperty("active_connections") val activeConnections: Long?,
            @param:JsonProperty("idle_connections") val idleConnections: Long?,
            @param:JsonProperty("max_connections") val maxConnections: Long?,
        )

        data class RedisData(
            val available: Boolean,
            @param:JsonProperty("latency_ms") val latencyMillis: Long,
            @param:JsonProperty("key_count") val keyCount: Long?,
            @param:JsonProperty("used_memory_bytes") val usedMemoryBytes: Long?,
            @param:JsonProperty("connected_clients") val connectedClients: Long?,
            val version: String?,
        )

        data class Response(
            val status: String,
            @param:JsonProperty("generated_at") val generatedAt: Instant,
            val application: ApplicationData,
            val jvm: JvmData,
            val database: DatabaseData,
            val redis: RedisData,
        )

        val status = systemStatusService.getStatus(adminId)
        val rs = Response(
            status = status.status.name,
            generatedAt = status.generatedAt,
            application = ApplicationData(
                name = status.application.name,
                version = status.application.version,
                startedAt = status.application.startedAt,
                uptimeSeconds = status.application.uptimeSeconds,
                availableProcessors = status.application.availableProcessors,
                systemLoadAverage = status.application.systemLoadAverage,
                processCpuUsage = status.application.processCpuUsage,
                systemCpuUsage = status.application.systemCpuUsage,
            ),
            jvm = JvmData(
                heapUsedBytes = status.jvm.heapUsedBytes,
                heapCommittedBytes = status.jvm.heapCommittedBytes,
                heapMaxBytes = status.jvm.heapMaxBytes,
                nonHeapUsedBytes = status.jvm.nonHeapUsedBytes,
                liveThreads = status.jvm.liveThreads,
                peakThreads = status.jvm.peakThreads,
                daemonThreads = status.jvm.daemonThreads,
                gcCollectionCount = status.jvm.gcCollectionCount,
                gcCollectionTimeMillis = status.jvm.gcCollectionTimeMillis,
            ),
            database = DatabaseData(
                available = status.database.available,
                latencyMillis = status.database.latencyMillis,
                activeConnections = status.database.activeConnections,
                idleConnections = status.database.idleConnections,
                maxConnections = status.database.maxConnections,
            ),
            redis = RedisData(
                available = status.redis.available,
                latencyMillis = status.redis.latencyMillis,
                keyCount = status.redis.keyCount,
                usedMemoryBytes = status.redis.usedMemoryBytes,
                connectedClients = status.redis.connectedClients,
                version = status.redis.version,
            ),
        )
        return builder.ok().data(rs).build()
    }
}
