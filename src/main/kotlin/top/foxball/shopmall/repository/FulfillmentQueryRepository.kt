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
    // 锚点必须是 order_items，再左连接当前 ALLOCATED 的 shipment_items / shipments。
    // 以 shipments 为锚点会在空运单、全释放运单等边界下漏算，导致整单 DELIVERED 误判。
    fun summarize(orderId: Long): FulfillmentSummary = jdbcTemplate.queryForObject(
        """
        WITH allocated AS (
            SELECT si.order_item_id AS order_item_id, s.id AS shipment_id, s.status, s.delivered_at
            FROM shipment_items si
            JOIN shipments s ON s.id = si.shipment_id
            WHERE s.order_id = ? AND si.allocation_status = 'ALLOCATED'
        )
        SELECT
            (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = ?) AS order_item_count,
            (SELECT COUNT(DISTINCT a.order_item_id) FROM allocated a) AS allocated_count,
            (SELECT COUNT(DISTINCT a.shipment_id) FROM allocated a WHERE a.status <> 'DELIVERED') AS non_delivered_count,
            (SELECT MAX(a.delivered_at) FROM allocated a) AS max_delivered_at
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
