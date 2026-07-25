package top.foxball.shopmall.service.impl

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisStreamCommands
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.repository.OutboxEventRepository
import java.time.Clock
import java.time.Duration

@Component
class OutboxRelayScheduler(
    private val repository: OutboxEventRepository,
    private val redis: StringRedisTemplate,
    private val properties: OrderProperties,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(fixedDelayString = "\${shopmall.order.outbox-relay-delay-ms:5000}")
    fun relay() {
        val now = clock.instant()
        val sentBefore = now.minusSeconds(properties.outboxAckSlaSeconds)
        repository.lockRelayBatch(now, sentBefore).forEach { event ->
            try {
                val eventId = requireNotNull(event.id)
                redis.opsForStream<String, String>().add(
                    MapRecord.create(
                        STREAM,
                        mapOf(
                            "outboxId" to eventId.toString(),
                            "aggregateType" to event.aggregateType,
                            "aggregateId" to event.aggregateId.toString(),
                            "eventType" to event.eventType,
                            "payload" to event.payload,
                        ),
                    ),
                    RedisStreamCommands.XAddOptions.maxlen(properties.streamMaxLen)
                        .approximateTrimming(true),
                )
                event.status = OutboxEvent.Status.SENT
                event.nextAttemptAt = now
            } catch (ex: Exception) {
                event.attempts += 1
                event.status = if (event.attempts >= properties.outboxMaxAttempts) {
                    OutboxEvent.Status.DEAD
                } else {
                    OutboxEvent.Status.PENDING
                }
                event.nextAttemptAt = now.plus(backoff(event.attempts))
                logger.error("Failed to relay outbox event {}", event.id, ex)
            }
        }
    }

    @Transactional
    @Scheduled(cron = "0 15 3 * * *")
    fun cleanAcknowledged() {
        repository.deleteAcknowledgedBefore(
            OutboxEvent.Status.ACKNOWLEDGED,
            clock.instant().minus(Duration.ofDays(properties.outboxRetentionDays)),
        )
    }

    private fun backoff(attempts: Int): Duration =
        Duration.ofSeconds((1L shl attempts.coerceIn(0, 8)).coerceAtMost(300))

    private companion object {
        const val STREAM = "order:events"
        val logger = LoggerFactory.getLogger(OutboxRelayScheduler::class.java)
    }
}
