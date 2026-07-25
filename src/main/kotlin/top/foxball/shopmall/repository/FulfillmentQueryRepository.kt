package top.foxball.shopmall.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

data class FulfillmentSummary(
    val orderItemCount: Long,
    val allocatedDistinctOrderItemCount: Long,
    val nonDeliveredAllocatedShipmentCount: Long,
    val maxAllocatedShipmentDeliveredAt: Instant?,
)

@Repository
class FulfillmentQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun summarize(orderId: Long): FulfillmentSummary = jdbcTemplate.queryForObject(
        """
        SELECT
            (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = ?) AS order_item_count,
            COUNT(DISTINCT CASE WHEN si.allocation_status = 'ALLOCATED' THEN si.order_item_id END) AS allocated_count,
            COUNT(DISTINCT CASE WHEN si.allocation_status = 'ALLOCATED' AND s.status <> 'DELIVERED' THEN s.id END) AS non_delivered_count,
            MAX(CASE WHEN si.allocation_status = 'ALLOCATED' THEN s.delivered_at END) AS max_delivered_at
        FROM shipments s
        LEFT JOIN shipment_items si ON si.shipment_id = s.id
        WHERE s.order_id = ?
        """.trimIndent(),
        { rs, _ ->
            FulfillmentSummary(
                orderItemCount = rs.getLong("order_item_count"),
                allocatedDistinctOrderItemCount = rs.getLong("allocated_count"),
                nonDeliveredAllocatedShipmentCount = rs.getLong("non_delivered_count"),
                maxAllocatedShipmentDeliveredAt = rs.getTimestamp("max_delivered_at")?.toInstant(),
            )
        },
        orderId,
        orderId,
    )
}
