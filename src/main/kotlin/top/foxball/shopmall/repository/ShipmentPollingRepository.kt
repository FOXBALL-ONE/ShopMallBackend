package top.foxball.shopmall.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

@Repository
class ShipmentPollingRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun claimDue(
        now: Instant,
        leaseOwner: String,
        leaseUntil: Instant,
        limit: Int,
    ): List<Long> = jdbcTemplate.query(
        """
        WITH due AS (
            SELECT id
            FROM shipments
            WHERE next_track_poll_at IS NOT NULL
              AND next_track_poll_at <= ?
              AND status IN ('IN_TRANSIT', 'OUT_FOR_DELIVERY')
              AND (poll_lease_until IS NULL OR poll_lease_until < ?)
            ORDER BY next_track_poll_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT ?
        )
        UPDATE shipments s
        SET poll_lease_owner = ?, poll_lease_until = ?
        FROM due
        WHERE s.id = due.id
        RETURNING s.id
        """.trimIndent(),
        { rs, _ -> rs.getLong("id") },
        Timestamp.from(now),
        Timestamp.from(now),
        limit,
        leaseOwner,
        Timestamp.from(leaseUntil),
    )

    fun clearExpiredRawPayloads(before: Instant): Int = jdbcTemplate.update(
        "UPDATE shipment_tracks SET raw = NULL WHERE raw IS NOT NULL AND received_at < ?",
        Timestamp.from(before),
    )
}
