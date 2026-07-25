package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.StripeWebhookEvent

interface StripeWebhookEventRepository : JpaRepository<StripeWebhookEvent, String> {
    @Modifying
    @Query(
        value = """
            insert into stripe_webhook_events(event_id, event_type, received_at)
            values (:eventId, :eventType, current_timestamp)
            on conflict (event_id) do nothing
        """,
        nativeQuery = true,
    )
    fun claim(
        @Param("eventId") eventId: String,
        @Param("eventType") eventType: String,
    ): Int
}
