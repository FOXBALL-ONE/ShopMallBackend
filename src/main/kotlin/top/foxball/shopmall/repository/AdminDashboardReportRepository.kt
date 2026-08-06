package top.foxball.shopmall.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class AdminDailyOrderCount(
    val date: LocalDate,
    val orders: Long,
    val paidOrders: Long,
)

data class AdminDailyRevenue(
    val date: LocalDate,
    val currency: String,
    val amount: BigDecimal,
)

data class AdminDailyCustomerCount(
    val date: LocalDate,
    val customers: Long,
)

/** 仪表盘趋势的批量聚合查询，避免按天循环访问数据库。 */
@Repository
class AdminDashboardReportRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findDailyOrderCounts(from: LocalDateTime, until: LocalDateTime): List<AdminDailyOrderCount> =
        jdbcTemplate.query(
            """
            SELECT CAST(created_at AS DATE) AS report_date,
                   COUNT(*) AS order_count,
                   SUM(CASE WHEN paid_at IS NOT NULL THEN 1 ELSE 0 END) AS paid_order_count
            FROM orders
            WHERE created_at >= ? AND created_at < ? AND status <> 'DELETED'
            GROUP BY CAST(created_at AS DATE)
            ORDER BY report_date
            """.trimIndent(),
            { rs, _ ->
                AdminDailyOrderCount(
                    date = rs.getDate("report_date").toLocalDate(),
                    orders = rs.getLong("order_count"),
                    paidOrders = rs.getLong("paid_order_count"),
                )
            },
            from,
            until,
        )

    fun findDailyRevenue(from: LocalDateTime, until: LocalDateTime): List<AdminDailyRevenue> =
        jdbcTemplate.query(
            """
            SELECT CAST(created_at AS DATE) AS report_date,
                   currency,
                   COALESCE(SUM(total_amount), 0) AS revenue
            FROM orders
            WHERE created_at >= ? AND created_at < ?
              AND status <> 'DELETED' AND paid_at IS NOT NULL
            GROUP BY CAST(created_at AS DATE), currency
            ORDER BY report_date, currency
            """.trimIndent(),
            { rs, _ ->
                AdminDailyRevenue(
                    date = rs.getDate("report_date").toLocalDate(),
                    currency = rs.getString("currency"),
                    amount = rs.getBigDecimal("revenue"),
                )
            },
            from,
            until,
        )

    fun findDailyCustomerCounts(from: LocalDateTime, until: LocalDateTime): List<AdminDailyCustomerCount> =
        jdbcTemplate.query(
            """
            SELECT CAST(created_at AS DATE) AS report_date, COUNT(*) AS customer_count
            FROM users
            WHERE created_at >= ? AND created_at < ?
              AND role = 'CUSTOMER' AND status <> 'DELETED'
            GROUP BY CAST(created_at AS DATE)
            ORDER BY report_date
            """.trimIndent(),
            { rs, _ ->
                AdminDailyCustomerCount(
                    date = rs.getDate("report_date").toLocalDate(),
                    customers = rs.getLong("customer_count"),
                )
            },
            from,
            until,
        )
}
