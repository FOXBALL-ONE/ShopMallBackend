package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import java.time.Instant

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    @Query(
        value = """
            select * from domain_outbox
            where (status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= :now))
               or (status = 'SENT' and next_attempt_at <= :sentBefore)
            order by created_at, id
            limit 100
            for update skip locked
        """,
        nativeQuery = true,
    )
    fun lockRelayBatch(
        @Param("now") now: Instant,
        @Param("sentBefore") sentBefore: Instant,
    ): List<OutboxEvent>

    @Modifying
    @Query(
        "delete from OutboxEvent e where e.status = :status and " +
            "e.acknowledgedAt is not null and e.acknowledgedAt < :before",
    )
    fun deleteAcknowledgedBefore(
        @Param("status") status: OutboxEvent.Status,
        @Param("before") before: Instant,
    ): Int
}
