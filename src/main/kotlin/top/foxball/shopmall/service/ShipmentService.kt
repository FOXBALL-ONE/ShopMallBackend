package top.foxball.shopmall.service

import top.foxball.shopmall.controller.AdminShipmentResponse
import top.foxball.shopmall.controller.CancelShipmentRequest
import top.foxball.shopmall.controller.CreateShipmentRequest
import top.foxball.shopmall.controller.CustomerShipmentResponse
import top.foxball.shopmall.controller.DispatchShipmentRequest
import top.foxball.shopmall.controller.ManualDeliveredRequest
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.logistics.TrackingEvent

interface ShipmentService {
    fun createShipment(
        orderNo: String,
        request: CreateShipmentRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse

    fun listAdmin(orderNo: String, adminId: Long): List<AdminShipmentResponse>

    fun listCustomer(orderNo: String, userId: Long): List<CustomerShipmentResponse>

    fun getCustomer(orderNo: String, shipmentNo: String, userId: Long): CustomerShipmentResponse

    fun trackByTrackingNumber(
        carrierCode: CarrierCode,
        trackingNo: String,
        userId: Long,
    ): CustomerShipmentResponse

    fun dispatchShipment(
        shipmentNo: String,
        request: DispatchShipmentRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse

    fun cancelShipment(
        shipmentNo: String,
        request: CancelShipmentRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse

    fun markManualDelivered(
        shipmentNo: String,
        request: ManualDeliveredRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse

    fun handleTrackingEvent(carrierCode: CarrierCode, event: TrackingEvent, source: TrackSource)

    fun reconcileOrderDelivery(orderId: Long)
}
