package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import top.foxball.shopmall.entity.jdbc.ShipmentTrack
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.logistics.TrackingEvent
import java.time.Instant

data class ShipmentDetails(
    val shipment: Shipment,
    val orderNo: String,
    val items: List<ShipmentItem>,
    val tracks: List<ShipmentTrack>,
)

interface ShipmentService {
    fun createShipment(
        orderNo: String,
        carrierCode: CarrierCode,
        trackingNo: String?,
        orderItemIds: List<Long>,
        quantities: List<Int>,
        note: String?,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails

    fun listAdmin(orderNo: String, adminId: Long): List<ShipmentDetails>

    fun listCustomer(orderNo: String, userId: Long): List<ShipmentDetails>

    fun getCustomer(orderNo: String, shipmentNo: String, userId: Long): ShipmentDetails

    fun trackByTrackingNumber(
        carrierCode: CarrierCode,
        trackingNo: String,
        userId: Long,
    ): ShipmentDetails

    fun dispatchShipment(
        shipmentNo: String,
        note: String?,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails

    fun cancelShipment(
        shipmentNo: String,
        reason: String,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails

    fun markManualDelivered(
        shipmentNo: String,
        occurredAt: Instant?,
        reason: String,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails

    fun handleTrackingEvent(carrierCode: CarrierCode, event: TrackingEvent, source: TrackSource)

    fun reconcileOrderDelivery(orderId: Long)
}
