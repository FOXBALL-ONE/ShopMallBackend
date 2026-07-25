package top.foxball.shopmall.repository

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.assertFailsWith

@Testcontainers(disabledWithoutDocker = true)
class LogisticsMigrationIntegrationTest {
    @Test
    fun `active allocation unique index allows replacement only after release`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO orders (
                        order_no, customer_id, status, currency, recipient_name, phone,
                        country_code, city, address_line1
                    ) VALUES ('O-1', 1, 'PAID', 'USD', 'Test User', '+14155550123', 'US', 'Austin', '1 Main St')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO order_items (
                        order_id, product_id, product_snapshot, unit_price, quantity, line_total
                    ) VALUES (1, 1, '{}', 10.00, 1, 10.00)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO shipments (
                        shipment_no, order_id, carrier_code, tracking_no, tracking_no_normalized,
                        status, recipient_name, phone, country_code, city, address_line1, created_by
                    ) VALUES
                        ('S-1', 1, 'MANUAL', 'T-1', 'T-1', 'LABEL_CREATED', 'Test User', '+14155550123', 'US', 'Austin', '1 Main St', 1),
                        ('S-2', 1, 'MANUAL', 'T-2', 'T-2', 'LABEL_CREATED', 'Test User', '+14155550123', 'US', 'Austin', '1 Main St', 1)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO shipment_items (
                        shipment_id, order_item_id, order_item_snapshot, quantity, allocation_status
                    ) VALUES (1, 1, '{}', 1, 'ALLOCATED')
                    """.trimIndent(),
                )
                assertFailsWith<java.sql.SQLException> {
                    statement.executeUpdate(
                        """
                        INSERT INTO shipment_items (
                            shipment_id, order_item_id, order_item_snapshot, quantity, allocation_status
                        ) VALUES (2, 1, '{}', 1, 'ALLOCATED')
                        """.trimIndent(),
                    )
                }
                statement.executeUpdate(
                    "UPDATE shipment_items SET allocation_status = 'RELEASED' WHERE shipment_id = 1",
                )
                statement.executeUpdate(
                    """
                    INSERT INTO shipment_items (
                        shipment_id, order_item_id, order_item_snapshot, quantity, allocation_status
                    ) VALUES (2, 1, '{}', 1, 'ALLOCATED')
                    """.trimIndent(),
                )
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:17-alpine")

        @BeforeAll
        @JvmStatic
        fun migrate() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}
