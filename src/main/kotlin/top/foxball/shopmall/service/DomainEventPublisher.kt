package top.foxball.shopmall.service

import org.springframework.stereotype.Component
import top.foxball.shopmall.entity.jdbc.OutboxEvent
import top.foxball.shopmall.repository.OutboxEventRepository

@Component
class DomainEventPublisher(
    private val repository: OutboxEventRepository,
) {
    fun publishInTx(
        aggregateType: String,
        aggregateId: Long,
        eventType: String,
        payload: String,
    ): OutboxEvent = repository.save(
        OutboxEvent(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            payload = payload,
        ),
    )
}
