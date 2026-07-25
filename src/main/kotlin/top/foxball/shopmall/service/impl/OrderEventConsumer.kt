package top.foxball.shopmall.service.impl

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisStreamCommands
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.domain.Range
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.config.OrderProperties
import java.time.Duration
import java.util.UUID

@Component
class OrderEventConsumer(
    private val redis: StringRedisTemplate,
    private val handler: OutboxMessageHandler,
    private val properties: OrderProperties,
) {
    private val consumer = Consumer.from(GROUP, "consumer-${UUID.randomUUID()}")
    @Volatile private var groupReady = false

    @Scheduled(fixedDelayString = "\${shopmall.order.stream-poll-delay-ms:1000}")
    fun poll() {
        try {
            ensureGroup()
            val records = redis.opsForStream<String, String>().read(
                consumer,
                StreamReadOptions.empty().count(20).block(Duration.ofMillis(250)),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()),
            ).orEmpty()
            records.forEach(::consume)
        } catch (ex: Exception) {
            if (ex.message?.contains("NOGROUP", ignoreCase = true) == true) groupReady = false
            logger.debug("Order stream polling unavailable", ex)
        }
    }

    @Scheduled(fixedDelayString = "\${shopmall.order.stream-reclaim-delay-ms:30000}")
    fun reclaimPending() {
        try {
            ensureGroup()
            val operations = redis.opsForStream<String, String>()
            val stale = operations.pending(
                STREAM,
                GROUP,
                Range.unbounded<String>(),
                100,
                Duration.ofSeconds(60),
            )
            if (stale.isEmpty) return
            val staleIds = stale.iterator().asSequence()
                .mapNotNull { it.id }
                .toList()
                .toTypedArray()
            val claimed = operations.claim(
                STREAM,
                GROUP,
                consumer.name,
                Duration.ofSeconds(60),
                *staleIds,
            )
            claimed.forEach(::consume)
        } catch (ex: Exception) {
            if (ex.message?.contains("NOGROUP", ignoreCase = true) == true) groupReady = false
            logger.debug("Order stream pending reclaim unavailable", ex)
        }
    }

    private fun consume(record: MapRecord<String, String, String>) {
        val values = record.value
        val outboxId = values["outboxId"]?.toLongOrNull()
        val aggregateId = values["aggregateId"]?.toLongOrNull()
        if (outboxId == null || aggregateId == null) {
            redis.opsForStream<String, String>().acknowledge(STREAM, GROUP, record.id)
            return
        }
        try {
            handler.handle(
                outboxId,
                values["aggregateType"].orEmpty(),
                aggregateId,
                values["eventType"].orEmpty(),
            )
        } catch (ex: Exception) {
            logger.error("Failed to consume outbox event {}", outboxId, ex)
            val exhausted = try {
                handler.recordFailure(outboxId)
            } catch (recordingFailure: Exception) {
                logger.error("Failed to record outbox consumer failure {}", outboxId, recordingFailure)
                return
            }
            if (exhausted) addDeadLetter(values)
        }
        redis.opsForStream<String, String>().acknowledge(STREAM, GROUP, record.id)
    }

    private fun ensureGroup() {
        if (groupReady) return
        val operations = redis.opsForStream<String, String>()
        val bootstrapId = operations.add(STREAM, mapOf("bootstrap" to "true"))
        try {
            operations.createGroup(STREAM, ReadOffset.latest(), GROUP)
        } catch (ex: Exception) {
            if (ex.message?.contains("BUSYGROUP", ignoreCase = true) != true) throw ex
        } finally {
            if (bootstrapId != null) operations.delete(STREAM, bootstrapId)
        }
        groupReady = true
    }

    private fun addDeadLetter(values: Map<String, String>) {
        redis.opsForStream<String, String>().add(
            DEAD_STREAM,
            values,
            RedisStreamCommands.XAddOptions.maxlen(properties.streamMaxLen).approximateTrimming(true),
        )
    }

    private companion object {
        const val STREAM = "order:events"
        const val DEAD_STREAM = "order:events:dead"
        const val GROUP = "order-consumer-group"
        val logger = LoggerFactory.getLogger(OrderEventConsumer::class.java)
    }
}
