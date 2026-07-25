package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.repository.OutboxEventRepository
import top.foxball.shopmall.service.OrderPaymentService
import java.time.Clock
import java.time.Duration

@Service
class OutboxMessageHandler(
    private val repository: OutboxEventRepository,
    private val paymentService: OrderPaymentService,
    private val shipmentOutboxProcessor: ShipmentOutboxProcessor,
    private val properties: OrderProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun handle(outboxId: Long, aggregateType: String, aggregateId: Long, eventType: String) {
        val shouldHandle = transactions.execute {
            val event = repository.findById(outboxId).orElse(null) ?: return@execute false
            event.status != OutboxEvent.Status.ACKNOWLEDGED
        } == true
        if (!shouldHandle) return

        when (aggregateType) {
            "ORDER" -> if (eventType == "PI_CREATE") paymentService.createPaymentIntent(aggregateId)
            "SHIPMENT" -> shipmentOutboxProcessor.handle(aggregateId, eventType)
        }

        transactions.executeWithoutResult {
            val event = repository.findById(outboxId).orElse(null) ?: return@executeWithoutResult
            event.status = OutboxEvent.Status.ACKNOWLEDGED
            event.acknowledgedAt = clock.instant()
            event.nextAttemptAt = null
        }
    }

    @Transactional
    fun recordFailure(outboxId: Long): Boolean {
        val event = repository.findById(outboxId).orElse(null) ?: return false
        event.attempts += 1
        val exhausted = event.attempts >= properties.outboxMaxAttempts
        event.status = if (exhausted) OutboxEvent.Status.NEEDS_REPLAY else OutboxEvent.Status.PENDING
        event.nextAttemptAt = if (exhausted) null else {
            clock.instant().plus(Duration.ofSeconds((1L shl event.attempts.coerceIn(0, 8)).coerceAtMost(300)))
        }
        return exhausted
    }
}
