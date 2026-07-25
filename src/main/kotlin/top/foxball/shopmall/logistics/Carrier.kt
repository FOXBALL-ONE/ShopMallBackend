package top.foxball.shopmall.logistics

import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.NormalizedTrackingStatus
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import java.time.Instant

interface Carrier {
    val code: CarrierCode
    val capabilities: CarrierCapabilities

    fun createLabel(request: LabelRequest): LabelResponse

    fun cancelLabel(request: CancelLabelRequest): CancelLabelResult

    fun queryTracking(trackingNo: String): List<TrackingEvent>

    fun parseWebhook(payload: ByteArray, headers: Map<String, List<String>>): List<TrackingEvent>

    fun normalizeTrackingNo(trackingNo: String): String

    fun trackingUrl(trackingNo: String): String?
}

data class CarrierCapabilities(
    val remoteLabel: Boolean,
    val webhook: Boolean,
    val polling: Boolean,
)

data class ShipmentItemSnapshot(
    val orderItemId: Long,
    val productSnapshot: String,
    val quantity: Int,
)

data class LabelRequest(
    val shipmentNo: String,
    val idempotencyReference: String = shipmentNo,
    val requestedTrackingNo: String?,
    val shippingAddress: OrderShippingAddress,
    val items: List<ShipmentItemSnapshot>,
)

data class LabelResponse(
    val labelUrl: String?,
    val trackingNo: String,
)

data class CancelLabelRequest(
    val shipmentNo: String,
    val trackingNo: String?,
    val idempotencyReference: String = "cancel:$shipmentNo",
)

enum class CancelLabelResult { CANCELLED_OR_NOT_FOUND, RETRYABLE_FAILURE }

data class TrackingEvent(
    val trackingNo: String,
    val carrierEventId: String,
    val statusCode: String,
    val normalizedStatus: NormalizedTrackingStatus,
    val location: String?,
    val description: String?,
    val occurredAt: Instant,
    val raw: String?,
)
