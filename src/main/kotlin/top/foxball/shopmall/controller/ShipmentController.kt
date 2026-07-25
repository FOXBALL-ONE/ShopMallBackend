package top.foxball.shopmall.controller

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.handler.ShipmentNotFoundException
import top.foxball.shopmall.service.ShipmentService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder

@RestController
class ShipmentController(
    private val shipmentService: ShipmentService,
    private val builder: ResponseBuilder,
) {
    @PostMapping("/api/admin/orders/{orderNo}/shipments")
    fun createShipment(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable orderNo: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateShipmentRequest,
    ): ResponseEntity<Response> = builder.status(HttpStatus.CREATED)
        .data(shipmentService.createShipment(orderNo, request, adminId, idempotencyKey))
        .build()

    @GetMapping("/api/admin/orders/{orderNo}/shipments")
    fun listAdmin(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable orderNo: String,
    ): ResponseEntity<Response> = builder.ok()
        .data(shipmentService.listAdmin(orderNo, adminId))
        .build()

    @GetMapping("/api/orders/{orderNo}/shipments")
    fun listCustomer(
        @AuthenticationPrincipal userId: Long,
        @PathVariable orderNo: String,
    ): ResponseEntity<Response> = builder.ok()
        .data(shipmentService.listCustomer(orderNo, userId))
        .build()

    @GetMapping("/api/orders/{orderNo}/shipments/{shipmentNo}")
    fun getCustomer(
        @AuthenticationPrincipal userId: Long,
        @PathVariable orderNo: String,
        @PathVariable shipmentNo: String,
    ): ResponseEntity<Response> = builder.ok()
        .data(shipmentService.getCustomer(orderNo, shipmentNo, userId))
        .build()

    @GetMapping("/api/logistics/track/{carrier}/{trackingNo}")
    fun track(
        @AuthenticationPrincipal userId: Long,
        @PathVariable carrier: String,
        @PathVariable trackingNo: String,
    ): ResponseEntity<Response> {
        val carrierCode = CarrierCode.fromPath(carrier) ?: throw ShipmentNotFoundException()
        return builder.ok()
            .data(shipmentService.trackByTrackingNumber(carrierCode, trackingNo, userId))
            .build()
    }

    @PostMapping("/api/admin/shipments/{shipmentNo}/dispatch")
    fun dispatch(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable shipmentNo: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: DispatchShipmentRequest,
    ): ResponseEntity<Response> = builder.ok()
        .data(shipmentService.dispatchShipment(shipmentNo, request, adminId, idempotencyKey))
        .build()

    @PostMapping("/api/admin/shipments/{shipmentNo}/cancel")
    fun cancel(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable shipmentNo: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CancelShipmentRequest,
    ): ResponseEntity<Response> = builder.ok()
        .data(shipmentService.cancelShipment(shipmentNo, request, adminId, idempotencyKey))
        .build()

    @PostMapping("/api/admin/shipments/{shipmentNo}/delivered")
    fun delivered(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable shipmentNo: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: ManualDeliveredRequest,
    ): ResponseEntity<Response> = builder.ok()
        .data(shipmentService.markManualDelivered(shipmentNo, request, adminId, idempotencyKey))
        .build()
}
