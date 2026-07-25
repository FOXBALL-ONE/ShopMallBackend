package top.foxball.shopmall.shared

import org.springframework.stereotype.Component
import top.foxball.shopmall.entity.jdbc.LogisticsIdempotency
import top.foxball.shopmall.handler.IdempotencyConflictException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.LogisticsIdempotencyRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
class LogisticsIdempotencyService(
    private val repository: LogisticsIdempotencyRepository,
) {
    fun requestHash(value: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun replayShipmentId(
        actorId: Long,
        operation: String,
        key: String,
        requestHash: String,
    ): Long? {
        validateKey(key)
        val existing = repository.findByActorIdAndOperationAndIdempotencyKey(actorId, operation, key)
            ?: return null
        if (existing.requestHash != requestHash) {
            throw IdempotencyConflictException()
        }
        return existing.shipmentId
    }

    fun record(
        actorId: Long,
        operation: String,
        key: String,
        requestHash: String,
        shipmentId: Long,
    ) {
        repository.save(
            LogisticsIdempotency(
                actorId = actorId,
                operation = operation,
                idempotencyKey = key,
                requestHash = requestHash,
                shipmentId = shipmentId,
            ),
        )
    }

    private fun validateKey(key: String) {
        if (key.isBlank() || key.length > 128) {
            throw ParamErrorException("Idempotency-Key length must be between 1 and 128")
        }
    }
}
