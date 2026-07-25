package top.foxball.shopmall.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import top.foxball.shopmall.entity.jdbc.AllocationStatus
import top.foxball.shopmall.entity.jdbc.CarrierCode
import java.time.Instant

data class CreateShipmentRequest(
    @field:NotNull
    val carrierCode: CarrierCode,

    @field:Size(max = 64)
    val trackingNo: String? = null,

    @field:NotNull
    @field:Size(min = 1, max = 50)
    @field:Valid
    val items: List<ShipmentItemRequest>,

    @field:Size(max = 200)
    val note: String? = null,
)

data class ShipmentItemRequest(
    @field:Min(1)
    val orderItemId: Long,

    @field:Min(1)
    val quantity: Int,
)

data class DispatchShipmentRequest(
    @field:Size(max = 200)
    val note: String? = null,
)

data class CancelShipmentRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val reason: String,
)

data class ManualDeliveredRequest(
    val occurredAt: Instant? = null,

    @field:NotBlank
    @field:Size(max = 200)
    val reason: String,
)

data class ShipmentItemResponse(
    val orderItemId: Long,
    val productSnapshot: String,
    val quantity: Int,
    val allocationStatus: AllocationStatus,
)

data class ShipmentTrackResponse(
    val carrierEventId: String,
    val statusCode: String,
    val normalizedStatus: String,
    val source: String,
    val location: String?,
    val description: String?,
    val occurredAt: Instant,
    val receivedAt: Instant?,
)

data class CustomerShipmentResponse(
    val shipmentNo: String,
    val orderNo: String,
    val carrier: String,
    val trackingNo: String?,
    val trackingUrl: String?,
    val status: String,
    val shippedAt: Instant?,
    val deliveredAt: Instant?,
    val lastTrackStatus: String?,
    val lastTrackLocation: String?,
    val lastTrackAt: Instant?,
    val items: List<ShipmentItemResponse>,
    val tracks: List<ShipmentTrackResponse>,
)

data class AdminShipmentResponse(
    val shipment: CustomerShipmentResponse,
    val carrierLabelUrl: String?,
    val createdBy: Long,
    val note: String?,
    val cancelReason: String?,
    val consecutiveTrackFailures: Int,
    val lastTrackError: String?,
)
