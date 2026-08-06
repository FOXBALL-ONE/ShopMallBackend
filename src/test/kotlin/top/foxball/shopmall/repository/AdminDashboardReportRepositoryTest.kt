package top.foxball.shopmall.repository

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

class AdminDashboardReportRepositoryTest {
    @Test
    fun `daily queries aggregate paid revenue and exclude deleted records`() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:dashboard-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE orders (
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                paid_at TIMESTAMP WITH TIME ZONE,
                status VARCHAR(24) NOT NULL,
                total_amount NUMERIC(12, 2) NOT NULL,
                currency VARCHAR(3) NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE users (
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                role VARCHAR(20) NOT NULL,
                status VARCHAR(20) NOT NULL
            )
            """.trimIndent(),
        )
        val firstDay = LocalDateTime.parse("2026-08-01T08:00:00")
        val secondDay = LocalDateTime.parse("2026-08-02T09:00:00")
        jdbcTemplate.update(
            "INSERT INTO orders (created_at, paid_at, status, total_amount, currency) VALUES (?, ?, ?, ?, ?)",
            firstDay,
            firstDay.plusHours(1),
            "PAID",
            BigDecimal("20.00"),
            "USD",
        )
        jdbcTemplate.update(
            "INSERT INTO orders (created_at, paid_at, status, total_amount, currency) VALUES (?, ?, ?, ?, ?)",
            firstDay.plusHours(2),
            null,
            "PENDING_PAYMENT",
            BigDecimal("30.00"),
            "USD",
        )
        jdbcTemplate.update(
            "INSERT INTO orders (created_at, paid_at, status, total_amount, currency) VALUES (?, ?, ?, ?, ?)",
            secondDay,
            secondDay.plusHours(1),
            "DELETED",
            BigDecimal("99.00"),
            "USD",
        )
        jdbcTemplate.update(
            "INSERT INTO users (created_at, role, status) VALUES (?, ?, ?)",
            firstDay,
            "CUSTOMER",
            "ACTIVE",
        )
        jdbcTemplate.update(
            "INSERT INTO users (created_at, role, status) VALUES (?, ?, ?)",
            firstDay.plusHours(1),
            "ADMIN",
            "ACTIVE",
        )

        val repository = AdminDashboardReportRepository(jdbcTemplate)
        val from = LocalDateTime.parse("2026-08-01T00:00:00")
        val until = LocalDateTime.parse("2026-08-03T00:00:00")

        assertEquals(
            listOf(AdminDailyOrderCount(firstDay.toLocalDate(), orders = 2, paidOrders = 1)),
            repository.findDailyOrderCounts(from, until),
        )
        assertEquals(
            listOf(AdminDailyRevenue(firstDay.toLocalDate(), "USD", BigDecimal("20.00"))),
            repository.findDailyRevenue(from, until),
        )
        assertEquals(
            listOf(AdminDailyCustomerCount(firstDay.toLocalDate(), customers = 1)),
            repository.findDailyCustomerCounts(from, until),
        )
    }
}
