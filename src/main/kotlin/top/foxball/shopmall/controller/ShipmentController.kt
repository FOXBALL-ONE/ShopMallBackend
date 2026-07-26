package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
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
    /**
     * @api 创建运单
     * @param orderNo 订单编号
     * @param idempotencyKey 幂等键
     * @param carrierCode 承运商代码
     * @param trackingNo 物流追踪号
     * @param orderItemIds 订单商品行 ID 列表
     * @param quantities 各订单商品行对应的发货数量
     * @param note 运单备注
     */
    @PostMapping("/api/admin/orders/{orderNo}/shipments")
    fun createShipment(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("orderNo") orderNo: String,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
        @RequestParam("carrier_code") carrierCode: CarrierCode,
        @RequestParam("tracking_no", required = false) @Size(max = 64) trackingNo: String?,
        @RequestParam("order_item_ids") @Size(min = 1, max = 50) orderItemIds: List<Long>,
        @RequestParam("quantities") @Size(min = 1, max = 50) quantities: List<Int>,
        @RequestParam("note", required = false) @Size(max = 200) note: String?,
    ): ResponseEntity<Response> {
        if (orderItemIds.size != quantities.size) {
            return builder.badRequest()
                .message("订单商品行与数量必须一一对应")
                .build()
        }
        if (orderItemIds.any { it < 1 } || quantities.any { it < 1 }) {
            return builder.badRequest()
                .message("订单商品行 ID 和数量必须大于 0")
                .build()
        }

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
            val shipment: ShipmentData,
            @param:JsonProperty("carrier_label_url")
            val carrierLabelUrl: String?,
            @param:JsonProperty("created_by")
            val createdBy: Long,
            val note: String?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("consecutive_track_failures")
            val consecutiveTrackFailures: Int,
            @param:JsonProperty("last_track_error")
            val lastTrackError: String?,
        )

        val details = shipmentService.createShipment(
            orderNo = orderNo,
            carrierCode = carrierCode,
            trackingNo = trackingNo,
            orderItemIds = orderItemIds,
            quantities = quantities,
            note = note,
            adminId = adminId,
            idempotencyKey = idempotencyKey,
        )
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
        val shipmentData = ShipmentData(
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
        val rs = Response(
            shipment = shipmentData,
            carrierLabelUrl = shipment.carrierLabelUrl,
            createdBy = shipment.createdBy,
            note = shipment.note,
            cancelReason = shipment.cancelReason,
            consecutiveTrackFailures = shipment.consecutiveTrackFailures,
            lastTrackError = shipment.lastTrackError,
        )
        return builder.status(HttpStatus.CREATED)
            .data(rs)
            .build()
    }

    /**
     * @api 获取订单的管理端运单列表
     * @param orderNo 订单编号
     */
    @GetMapping("/api/admin/orders/{orderNo}/shipments")
    fun getAdminShipments(
        @AuthenticationPrincipal adminId: Long,
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

        data class ShipmentAdminData(
            val shipment: ShipmentData,
            @param:JsonProperty("carrier_label_url")
            val carrierLabelUrl: String?,
            @param:JsonProperty("created_by")
            val createdBy: Long,
            val note: String?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("consecutive_track_failures")
            val consecutiveTrackFailures: Int,
            @param:JsonProperty("last_track_error")
            val lastTrackError: String?,
        )

        data class Response(
            val list: List<ShipmentAdminData>,
        )

        val detailsList = shipmentService.listAdmin(orderNo, adminId)
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
            val shipmentData = ShipmentData(
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
            ShipmentAdminData(
                shipment = shipmentData,
                carrierLabelUrl = shipment.carrierLabelUrl,
                createdBy = shipment.createdBy,
                note = shipment.note,
                cancelReason = shipment.cancelReason,
                consecutiveTrackFailures = shipment.consecutiveTrackFailures,
                lastTrackError = shipment.lastTrackError,
            )
        }
        val rs = Response(list)
        return builder.ok()
            .data(rs)
            .build()
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

    /**
     * @api 确认运单发出
     * @param shipmentNo 运单编号
     * @param idempotencyKey 幂等键
     * @param note 运单备注
     */
    @PostMapping("/api/admin/shipments/{shipmentNo}/dispatch")
    fun dispatchShipment(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("shipmentNo") shipmentNo: String,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
        @RequestParam("note", required = false) @Size(max = 200) note: String?,
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
            val shipment: ShipmentData,
            @param:JsonProperty("carrier_label_url")
            val carrierLabelUrl: String?,
            @param:JsonProperty("created_by")
            val createdBy: Long,
            val note: String?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("consecutive_track_failures")
            val consecutiveTrackFailures: Int,
            @param:JsonProperty("last_track_error")
            val lastTrackError: String?,
        )

        val details = shipmentService.dispatchShipment(shipmentNo, note, adminId, idempotencyKey)
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
        val shipmentData = ShipmentData(
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
        val rs = Response(
            shipment = shipmentData,
            carrierLabelUrl = shipment.carrierLabelUrl,
            createdBy = shipment.createdBy,
            note = shipment.note,
            cancelReason = shipment.cancelReason,
            consecutiveTrackFailures = shipment.consecutiveTrackFailures,
            lastTrackError = shipment.lastTrackError,
        )
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 取消运单
     * @param shipmentNo 运单编号
     * @param idempotencyKey 幂等键
     * @param reason 取消原因
     */
    @PostMapping("/api/admin/shipments/{shipmentNo}/cancel")
    fun cancelShipment(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("shipmentNo") shipmentNo: String,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
        @RequestParam("reason") @NotBlank @Size(max = 200) reason: String,
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
            val shipment: ShipmentData,
            @param:JsonProperty("carrier_label_url")
            val carrierLabelUrl: String?,
            @param:JsonProperty("created_by")
            val createdBy: Long,
            val note: String?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("consecutive_track_failures")
            val consecutiveTrackFailures: Int,
            @param:JsonProperty("last_track_error")
            val lastTrackError: String?,
        )

        val details = shipmentService.cancelShipment(shipmentNo, reason, adminId, idempotencyKey)
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
        val shipmentData = ShipmentData(
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
        val rs = Response(
            shipment = shipmentData,
            carrierLabelUrl = shipment.carrierLabelUrl,
            createdBy = shipment.createdBy,
            note = shipment.note,
            cancelReason = shipment.cancelReason,
            consecutiveTrackFailures = shipment.consecutiveTrackFailures,
            lastTrackError = shipment.lastTrackError,
        )
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 手动确认运单签收
     * @param shipmentNo 运单编号
     * @param idempotencyKey 幂等键
     * @param occurredAt 签收时间
     * @param reason 手动签收原因
     */
    @PostMapping("/api/admin/shipments/{shipmentNo}/delivered")
    fun markShipmentDelivered(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("shipmentNo") shipmentNo: String,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
        @RequestParam("occurred_at", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        occurredAt: Instant?,
        @RequestParam("reason") @NotBlank @Size(max = 200) reason: String,
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
            val shipment: ShipmentData,
            @param:JsonProperty("carrier_label_url")
            val carrierLabelUrl: String?,
            @param:JsonProperty("created_by")
            val createdBy: Long,
            val note: String?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("consecutive_track_failures")
            val consecutiveTrackFailures: Int,
            @param:JsonProperty("last_track_error")
            val lastTrackError: String?,
        )

        val details = shipmentService.markManualDelivered(
            shipmentNo = shipmentNo,
            occurredAt = occurredAt,
            reason = reason,
            adminId = adminId,
            idempotencyKey = idempotencyKey,
        )
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
        val shipmentData = ShipmentData(
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
        val rs = Response(
            shipment = shipmentData,
            carrierLabelUrl = shipment.carrierLabelUrl,
            createdBy = shipment.createdBy,
            note = shipment.note,
            cancelReason = shipment.cancelReason,
            consecutiveTrackFailures = shipment.consecutiveTrackFailures,
            lastTrackError = shipment.lastTrackError,
        )
        return builder.ok()
            .data(rs)
            .build()
    }
}
