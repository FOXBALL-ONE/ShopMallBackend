package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.handler.ShipmentNotFoundException
import top.foxball.shopmall.service.ShipmentService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.Instant

/**
 * @folder 物流/运单
 */
@Validated
@RestController
class ShipmentController(
    private val shipmentService: ShipmentService,
    private val builder: ResponseBuilder,
) {

    @PostMapping("/api/orders/{order_no}/shipments/{shipment_no}/delivered")
    fun markCustomerShipmentDelivered(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("order_no") orderNo: String,
        @PathVariable("shipment_no") shipmentNo: String,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 128) idempotencyKey: String,
    ): ResponseEntity<Response> {
        data class ItemData(
            @param:JsonProperty("order_item_id") val orderItemId: Long,
            @param:JsonProperty("product_snapshot") val productSnapshot: String,
            val quantity: Int,
            @param:JsonProperty("allocation_status") val allocationStatus: String,
        )
        data class TrackData(
            @param:JsonProperty("carrier_event_id") val carrierEventId: String,
            @param:JsonProperty("status_code") val statusCode: String,
            @param:JsonProperty("normalized_status") val normalizedStatus: String,
            val source: String,
            val location: String?,
            val description: String?,
            @param:JsonProperty("occurred_at") val occurredAt: Instant,
            @param:JsonProperty("received_at") val receivedAt: Instant?,
        )
        data class Response(
            @param:JsonProperty("shipment_no") val shipmentNo: String,
            @param:JsonProperty("order_no") val orderNo: String,
            val carrier: String,
            @param:JsonProperty("tracking_no") val trackingNo: String?,
            @param:JsonProperty("tracking_url") val trackingUrl: String?,
            val status: String,
            @param:JsonProperty("shipped_at") val shippedAt: Instant?,
            @param:JsonProperty("delivered_at") val deliveredAt: Instant?,
            @param:JsonProperty("last_track_status") val lastTrackStatus: String?,
            @param:JsonProperty("last_track_location") val lastTrackLocation: String?,
            @param:JsonProperty("last_track_at") val lastTrackAt: Instant?,
            val items: List<ItemData>,
            val tracks: List<TrackData>,
        )
        val details = shipmentService.markCustomerDelivered(orderNo, shipmentNo, userId, idempotencyKey)
        val shipment = details.shipment
        val rs = Response(
            shipmentNo = shipment.shipmentNo,
            orderNo = details.orderNo,
            carrier = shipment.carrierCode.pathValue,
            trackingNo = shipment.trackingNo,
            trackingUrl = shipment.trackingUrl,
            status = shipment.status.name,
            shippedAt = shipment.shippedAt,
            deliveredAt = shipment.deliveredAt,
            lastTrackStatus = shipment.lastTrackStatus,
            lastTrackLocation = shipment.lastTrackLocation,
            lastTrackAt = shipment.lastTrackAt,
            items = details.items.map { ItemData(it.orderItemId, it.orderItemSnapshot, it.quantity, it.allocationStatus.name) },
            tracks = details.tracks.map { TrackData(it.carrierEventId, it.statusCode, it.normalizedStatus.name, it.source.name, it.location, it.description, it.occurredAt, it.receivedAt) },
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取订单的用户端运单列表
     * @param orderNo 订单编号
     */
    @GetMapping("/api/orders/{orderNo}/shipments")
    fun getCustomerShipments(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("orderNo") orderNo: String,
    ): ResponseEntity<Response> {
        data class ItemData(
            @param:JsonProperty("order_item_id")
            val orderItemId: Long,
            @param:JsonProperty("product_snapshot")
            val productSnapshot: String,
            val quantity: Int,
            @param:JsonProperty("allocation_status")
            val allocationStatus: String,
        )

        data class TrackData(
            @param:JsonProperty("carrier_event_id")
            val carrierEventId: String,
            @param:JsonProperty("status_code")
            val statusCode: String,
            @param:JsonProperty("normalized_status")
            val normalizedStatus: String,
            val source: String,
            val location: String?,
            val description: String?,
            @param:JsonProperty("occurred_at")
            val occurredAt: Instant,
            @param:JsonProperty("received_at")
            val receivedAt: Instant?,
        )

        data class ShipmentData(
            @param:JsonProperty("shipment_no")
            val shipmentNo: String,
            @param:JsonProperty("order_no")
            val orderNo: String,
            val carrier: String,
            @param:JsonProperty("tracking_no")
            val trackingNo: String?,
            @param:JsonProperty("tracking_url")
            val trackingUrl: String?,
            val status: String,
            @param:JsonProperty("shipped_at")
            val shippedAt: Instant?,
            @param:JsonProperty("delivered_at")
            val deliveredAt: Instant?,
            @param:JsonProperty("last_track_status")
            val lastTrackStatus: String?,
            @param:JsonProperty("last_track_location")
            val lastTrackLocation: String?,
            @param:JsonProperty("last_track_at")
            val lastTrackAt: Instant?,
            val items: List<ItemData>,
            val tracks: List<TrackData>,
        )

        data class Response(
            val list: List<ShipmentData>,
        )

        val detailsList = shipmentService.listCustomer(orderNo, userId)
        val list = detailsList.map { details ->
            val shipment = details.shipment
            val items = details.items.map {
                ItemData(
                    orderItemId = it.orderItemId,
                    productSnapshot = it.orderItemSnapshot,
                    quantity = it.quantity,
                    allocationStatus = it.allocationStatus.name,
                )
            }
            val tracks = details.tracks.map {
                TrackData(
                    carrierEventId = it.carrierEventId,
                    statusCode = it.statusCode,
                    normalizedStatus = it.normalizedStatus.name,
                    source = it.source.name,
                    location = it.location,
                    description = it.description,
                    occurredAt = it.occurredAt,
                    receivedAt = it.receivedAt,
                )
            }
            ShipmentData(
                shipmentNo = shipment.shipmentNo,
                orderNo = details.orderNo,
                carrier = shipment.carrierCode.pathValue,
                trackingNo = shipment.trackingNo,
                trackingUrl = shipment.trackingUrl,
                status = shipment.status.name,
                shippedAt = shipment.shippedAt,
                deliveredAt = shipment.deliveredAt,
                lastTrackStatus = shipment.lastTrackStatus,
                lastTrackLocation = shipment.lastTrackLocation,
                lastTrackAt = shipment.lastTrackAt,
                items = items,
                tracks = tracks,
            )
        }
        val rs = Response(list)
        return builder.ok()
            .data(rs)
            .build()
    }


    /**
     * @api 获取用户端运单详情
     * @param orderNo 订单编号
     * @param shipmentNo 运单编号
     */
    @GetMapping("/api/orders/{orderNo}/shipments/{shipmentNo}")
    fun getCustomerShipment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("orderNo") orderNo: String,
        @PathVariable("shipmentNo") shipmentNo: String,
    ): ResponseEntity<Response> {
        data class ItemData(
            @param:JsonProperty("order_item_id")
            val orderItemId: Long,
            @param:JsonProperty("product_snapshot")
            val productSnapshot: String,
            val quantity: Int,
            @param:JsonProperty("allocation_status")
            val allocationStatus: String,
        )

        data class TrackData(
            @param:JsonProperty("carrier_event_id")
            val carrierEventId: String,
            @param:JsonProperty("status_code")
            val statusCode: String,
            @param:JsonProperty("normalized_status")
            val normalizedStatus: String,
            val source: String,
            val location: String?,
            val description: String?,
            @param:JsonProperty("occurred_at")
            val occurredAt: Instant,
            @param:JsonProperty("received_at")
            val receivedAt: Instant?,
        )

        data class Response(
            @param:JsonProperty("shipment_no")
            val shipmentNo: String,
            @param:JsonProperty("order_no")
            val orderNo: String,
            val carrier: String,
            @param:JsonProperty("tracking_no")
            val trackingNo: String?,
            @param:JsonProperty("tracking_url")
            val trackingUrl: String?,
            val status: String,
            @param:JsonProperty("shipped_at")
            val shippedAt: Instant?,
            @param:JsonProperty("delivered_at")
            val deliveredAt: Instant?,
            @param:JsonProperty("last_track_status")
            val lastTrackStatus: String?,
            @param:JsonProperty("last_track_location")
            val lastTrackLocation: String?,
            @param:JsonProperty("last_track_at")
            val lastTrackAt: Instant?,
            val items: List<ItemData>,
            val tracks: List<TrackData>,
        )

        val details = shipmentService.getCustomer(orderNo, shipmentNo, userId)
        val shipment = details.shipment
        val items = details.items.map {
            ItemData(
                orderItemId = it.orderItemId,
                productSnapshot = it.orderItemSnapshot,
                quantity = it.quantity,
                allocationStatus = it.allocationStatus.name,
            )
        }
        val tracks = details.tracks.map {
            TrackData(
                carrierEventId = it.carrierEventId,
                statusCode = it.statusCode,
                normalizedStatus = it.normalizedStatus.name,
                source = it.source.name,
                location = it.location,
                description = it.description,
                occurredAt = it.occurredAt,
                receivedAt = it.receivedAt,
            )
        }
        val rs = Response(
            shipmentNo = shipment.shipmentNo,
            orderNo = details.orderNo,
            carrier = shipment.carrierCode.pathValue,
            trackingNo = shipment.trackingNo,
            trackingUrl = shipment.trackingUrl,
            status = shipment.status.name,
            shippedAt = shipment.shippedAt,
            deliveredAt = shipment.deliveredAt,
            lastTrackStatus = shipment.lastTrackStatus,
            lastTrackLocation = shipment.lastTrackLocation,
            lastTrackAt = shipment.lastTrackAt,
            items = items,
            tracks = tracks,
        )
        return builder.ok()
            .data(rs)
            .build()
    }


    /**
     * @api 按承运商和追踪号查询物流
     * @param carrier 承运商代码
     * @param trackingNo 物流追踪号
     */
    @GetMapping("/api/logistics/track/{carrier}/{trackingNo}")
    fun trackShipment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("carrier") carrier: String,
        @PathVariable("trackingNo") trackingNo: String,
    ): ResponseEntity<Response> {
        data class ItemData(
            @param:JsonProperty("order_item_id")
            val orderItemId: Long,
            @param:JsonProperty("product_snapshot")
            val productSnapshot: String,
            val quantity: Int,
            @param:JsonProperty("allocation_status")
            val allocationStatus: String,
        )

        data class TrackData(
            @param:JsonProperty("carrier_event_id")
            val carrierEventId: String,
            @param:JsonProperty("status_code")
            val statusCode: String,
            @param:JsonProperty("normalized_status")
            val normalizedStatus: String,
            val source: String,
            val location: String?,
            val description: String?,
            @param:JsonProperty("occurred_at")
            val occurredAt: Instant,
            @param:JsonProperty("received_at")
            val receivedAt: Instant?,
        )

        data class Response(
            @param:JsonProperty("shipment_no")
            val shipmentNo: String,
            @param:JsonProperty("order_no")
            val orderNo: String,
            val carrier: String,
            @param:JsonProperty("tracking_no")
            val trackingNo: String?,
            @param:JsonProperty("tracking_url")
            val trackingUrl: String?,
            val status: String,
            @param:JsonProperty("shipped_at")
            val shippedAt: Instant?,
            @param:JsonProperty("delivered_at")
            val deliveredAt: Instant?,
            @param:JsonProperty("last_track_status")
            val lastTrackStatus: String?,
            @param:JsonProperty("last_track_location")
            val lastTrackLocation: String?,
            @param:JsonProperty("last_track_at")
            val lastTrackAt: Instant?,
            val items: List<ItemData>,
            val tracks: List<TrackData>,
        )

        val carrierCode = CarrierCode.fromPath(carrier) ?: throw ShipmentNotFoundException()
        val details = shipmentService.trackByTrackingNumber(carrierCode, trackingNo, userId)
        val shipment = details.shipment
        val items = details.items.map {
            ItemData(
                orderItemId = it.orderItemId,
                productSnapshot = it.orderItemSnapshot,
                quantity = it.quantity,
                allocationStatus = it.allocationStatus.name,
            )
        }
        val tracks = details.tracks.map {
            TrackData(
                carrierEventId = it.carrierEventId,
                statusCode = it.statusCode,
                normalizedStatus = it.normalizedStatus.name,
                source = it.source.name,
                location = it.location,
                description = it.description,
                occurredAt = it.occurredAt,
                receivedAt = it.receivedAt,
            )
        }
        val rs = Response(
            shipmentNo = shipment.shipmentNo,
            orderNo = details.orderNo,
            carrier = shipment.carrierCode.pathValue,
            trackingNo = shipment.trackingNo,
            trackingUrl = shipment.trackingUrl,
            status = shipment.status.name,
            shippedAt = shipment.shippedAt,
            deliveredAt = shipment.deliveredAt,
            lastTrackStatus = shipment.lastTrackStatus,
            lastTrackLocation = shipment.lastTrackLocation,
            lastTrackAt = shipment.lastTrackAt,
            items = items,
            tracks = tracks,
        )
        return builder.ok()
            .data(rs)
            .build()
    }


}
