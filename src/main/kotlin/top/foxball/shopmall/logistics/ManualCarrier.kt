package top.foxball.shopmall.logistics

import org.springframework.stereotype.Component
import top.foxball.shopmall.entity.jdbc.CarrierCode

@Component
class ManualCarrier : Carrier {
    override val code = CarrierCode.MANUAL
    override val capabilities = CarrierCapabilities(
        remoteLabel = false,
        webhook = false,
        polling = false,
    )

    override fun createLabel(request: LabelRequest): LabelResponse =
        throw UnsupportedOperationException("MANUAL does not create remote labels")

    override fun cancelLabel(request: CancelLabelRequest): CancelLabelResult =
        throw UnsupportedOperationException("MANUAL does not cancel remote labels")

    override fun queryTracking(trackingNo: String): List<TrackingEvent> =
        throw UnsupportedOperationException("MANUAL does not support polling")

    override fun parseWebhook(payload: ByteArray, headers: Map<String, List<String>>): List<TrackingEvent> =
        throw UnsupportedOperationException("MANUAL does not accept webhooks")

    override fun normalizeTrackingNo(trackingNo: String): String = trackingNo.trim().uppercase()

    override fun trackingUrl(trackingNo: String): String? = null
}
