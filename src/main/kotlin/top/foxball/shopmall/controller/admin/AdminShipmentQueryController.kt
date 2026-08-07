package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.service.AdminShipmentQuery
import top.foxball.shopmall.service.ShipmentService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.Instant

/**
 * @folder 管理端/物流/运单查询
 */
@Validated
@RestController
@RequestMapping("/admin/api/shipments")
class AdminShipmentQueryController(
    private val shipmentService: ShipmentService,
    private val userService: UserService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 分页查询全部运单
     * @param page 分页页码
     * @param pageSize 每页数量
     * @param status 运单状态
     * @param carrierCode 承运商代码
     * @param orderNo 订单编号
     * @param trackingNo 物流追踪号
     * @param hasError 是否存在物流错误
     */
    @GetMapping
    fun getShipments(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
        @RequestParam("status", required = false) status: ShipmentStatus?,
        @RequestParam("carrier", required = false) carrierCode: String?,
        @RequestParam("order_no", required = false) @Size(max = 32) orderNo: String?,
        @RequestParam("tracking_no", required = false) @Size(max = 64) trackingNo: String?,
        @RequestParam("has_error", required = false) hasError: Boolean?,
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
            @param:JsonProperty("created_by_username")
            val createdByUsername: String,
            val note: String?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("consecutive_track_failures")
            val consecutiveTrackFailures: Int,
            @param:JsonProperty("last_track_error")
            val lastTrackError: String?,
        )

        data class Pagination(
            val page: Int,
            val size: Int,
            @param:JsonProperty("total_items")
            val totalItems: Long,
            @param:JsonProperty("total_pages")
            val totalPages: Int,
        )

        data class Response(
            val list: List<ShipmentAdminData>,
            val pagination: Pagination,
        )

        val carrier = carrierCode?.let {
            CarrierCode.fromPath(it) ?: throw ParamErrorException("不支持的承运商代码")
        }
        val pagedData = shipmentService.listAdmin(
            adminId,
            AdminShipmentQuery(
                page = page - 1,
                size = pageSize,
                status = status,
                carrier = carrier,
                orderNo = orderNo,
                trackingNo = trackingNo,
                hasError = hasError,
            ),
        )
        val usernamesById = userService.getUsernamesByIds(
            pagedData.content.map { it.shipment.createdBy }.distinct(),
        )
        val list = pagedData.content.map { details ->
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
            ShipmentAdminData(
                shipment = ShipmentData(
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
                ),
                carrierLabelUrl = shipment.carrierLabelUrl,
                createdBy = shipment.createdBy,
                createdByUsername = requireNotNull(usernamesById[shipment.createdBy]) { "运单创建人不存在" },
                note = shipment.note,
                cancelReason = shipment.cancelReason,
                consecutiveTrackFailures = shipment.consecutiveTrackFailures,
                lastTrackError = shipment.lastTrackError,
            )
        }
        val rs = Response(
            list = list,
            pagination = Pagination(page, pageSize, pagedData.totalElements, pagedData.totalPages),
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端运单详情
     * @param shipmentNo 运单编号
     */
    @GetMapping("/{shipment_no}")
    fun getShipment(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("shipment_no") shipmentNo: String,
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
            @param:JsonProperty("created_by_username")
            val createdByUsername: String,
            val note: String?,
            @param:JsonProperty("cancel_reason")
            val cancelReason: String?,
            @param:JsonProperty("consecutive_track_failures")
            val consecutiveTrackFailures: Int,
            @param:JsonProperty("last_track_error")
            val lastTrackError: String?,
        )

        val details = shipmentService.getAdmin(shipmentNo, adminId)
        val shipment = details.shipment
        val createdByUsername = requireNotNull(userService.getUsernameById(shipment.createdBy)) { "运单创建人不存在" }
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
            shipment = ShipmentData(
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
            ),
            carrierLabelUrl = shipment.carrierLabelUrl,
            createdBy = shipment.createdBy,
            createdByUsername = createdByUsername,
            note = shipment.note,
            cancelReason = shipment.cancelReason,
            consecutiveTrackFailures = shipment.consecutiveTrackFailures,
            lastTrackError = shipment.lastTrackError,
        )
        return builder.ok().data(rs).build()
    }
}
