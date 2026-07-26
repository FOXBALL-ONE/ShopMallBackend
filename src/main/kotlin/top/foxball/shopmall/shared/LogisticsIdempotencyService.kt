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
    // 契约说明：requestHash 对入参执行 value.toString() 后做 SHA-256，本身不做语义规范化。
    // 调用方负责保证传入字符串的内容稳定：若拼入含 List（如 CreateShipmentRequest.items）的 toString，
    // 必须自行对集合元素排序后再拼接，否则 items 顺序调换会导致 hash 不一致，触发 IdempotencyConflictException 409。
    // 本方法仅被 ShipmentServiceImpl 调用（createShipment/dispatchShipment/cancelShipment/markManualDelivered），
    // 顺序敏感性的真正修复需在调用方拼接处完成，不应在此引入对特定 DTO 的耦合。
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
