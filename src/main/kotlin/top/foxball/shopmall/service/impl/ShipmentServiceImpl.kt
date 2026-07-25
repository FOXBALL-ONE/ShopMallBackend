package top.foxball.shopmall.service.impl

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.controller.AdminShipmentResponse
import top.foxball.shopmall.controller.CancelShipmentRequest
import top.foxball.shopmall.controller.CreateShipmentRequest
import top.foxball.shopmall.controller.CustomerShipmentResponse
import top.foxball.shopmall.controller.DispatchShipmentRequest
import top.foxball.shopmall.controller.ManualDeliveredRequest
import top.foxball.shopmall.controller.ShipmentItemResponse
import top.foxball.shopmall.controller.ShipmentTrackResponse
import top.foxball.shopmall.entity.jdbc.AllocationStatus
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.NormalizedTrackingStatus
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.handler.OrderNotFoundException
import top.foxball.shopmall.handler.OrderStatusException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ShipmentAllocationConflictException
import top.foxball.shopmall.handler.ShipmentNotFoundException
import top.foxball.shopmall.handler.ShipmentStatusException
import top.foxball.shopmall.logistics.Carrier
import top.foxball.shopmall.logistics.CarrierRegistry
import top.foxball.shopmall.logistics.TrackingEvent
import top.foxball.shopmall.repository.FulfillmentQueryRepository
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.ShipmentTrackRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.ShipmentService
import top.foxball.shopmall.shared.LogisticsIdempotencyService
import top.foxball.shopmall.shared.ShipmentNoGenerator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Service
@Transactional(readOnly = true)
class ShipmentServiceImpl(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val shipmentItemRepository: ShipmentItemRepository,
    private val shipmentTrackRepository: ShipmentTrackRepository,
    private val fulfillmentQueryRepository: FulfillmentQueryRepository,
    private val carrierRegistry: CarrierRegistry,
    private val adminAccessService: AdminAccessService,
    private val eventPublisher: DomainEventPublisher,
    private val idempotencyService: LogisticsIdempotencyService,
    private val shipmentNoGenerator: ShipmentNoGenerator,
    private val entityManager: EntityManager,
    private val clock: Clock,
) : ShipmentService {

    @Transactional
    override fun createShipment(
        orderNo: String,
        request: CreateShipmentRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse {
        adminAccessService.requireAdmin(adminId)
        val requestHash = idempotencyService.requestHash("$orderNo|$request")
        val order = orderRepository.lockByOrderNo(orderNo) ?: throw OrderNotFoundException()
        replay(adminId, CREATE_SHIPMENT, idempotencyKey, requestHash)?.let {
            return adminResponse(it, order.orderNo)
        }
        if (order.status !in setOf(OrderStatus.PAID, OrderStatus.SHIPPED)) {
            throw OrderStatusException("当前订单不可创建运单")
        }
        val orderId = requireNotNull(order.id)
        val requestIds = request.items.map { it.orderItemId }
        if (requestIds.distinct().size != requestIds.size) {
            throw ParamErrorException("运单商品行不能重复")
        }
        val orderItems = orderItemRepository.findAllByIdForOrder(requestIds, orderId)
        if (orderItems.size != requestIds.size) {
            throw ParamErrorException("运单包含不属于该订单的商品行")
        }
        val quantities = request.items.associate { it.orderItemId to it.quantity }
        if (orderItems.any { quantities[it.id] != it.quantity }) {
            throw ParamErrorException("本期仅支持完整分配订单行数量")
        }
        if (shipmentItemRepository.countActiveAllocations(requestIds) > 0) {
            throw ShipmentAllocationConflictException()
        }

        val carrier = carrierRegistry.find(request.carrierCode)
            ?: throw ParamErrorException("承运商未启用或尚未接入")
        val trackingNo = request.trackingNo?.trim()?.takeIf { it.isNotEmpty() }
        if (!carrier.capabilities.remoteLabel && trackingNo == null) {
            throw ParamErrorException("该承运商创建运单时必须提供 trackingNo")
        }
        val shipment = shipmentRepository.saveAndFlush(
            Shipment(
                shipmentNo = shipmentNoGenerator.next(),
                orderId = orderId,
                carrierCode = request.carrierCode,
                trackingNo = trackingNo,
                trackingNoNormalized = trackingNo?.let(carrier::normalizeTrackingNo),
                status = if (carrier.capabilities.remoteLabel) {
                    ShipmentStatus.LABEL_PENDING
                } else {
                    ShipmentStatus.LABEL_CREATED
                },
                shippingAddress = order.shippingAddress.copySnapshot(),
                trackingUrl = trackingNo?.let(carrier::trackingUrl),
                createdBy = adminId,
                note = request.note,
            ),
        )
        shipmentItemRepository.saveAllAndFlush(
            orderItems.map { item ->
                ShipmentItem(
                    shipment = shipment,
                    orderItemId = requireNotNull(item.id),
                    orderItemSnapshot = item.productSnapshot,
                    quantity = item.quantity,
                )
            },
        )
        val shipmentId = requireNotNull(shipment.id)
        if (shipment.status == ShipmentStatus.LABEL_PENDING) {
            eventPublisher.publishInTx(
                "SHIPMENT",
                shipmentId,
                "SHIPMENT_LABEL_REQUESTED",
                "{\"shipmentId\":$shipmentId}",
            )
        }
        idempotencyService.record(adminId, CREATE_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        entityManager.flush()
        return adminResponse(shipmentId)
    }

    override fun listAdmin(orderNo: String, adminId: Long): List<AdminShipmentResponse> {
        adminAccessService.requireAdmin(adminId)
        val order = orderRepository.findByOrderNo(orderNo) ?: throw OrderNotFoundException()
        return shipmentRepository.findAllByOrderIdOrderByCreatedAtAsc(requireNotNull(order.id))
            .map { adminResponse(requireNotNull(it.id), order.orderNo) }
    }

    override fun listCustomer(orderNo: String, userId: Long): List<CustomerShipmentResponse> {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, userId) ?: throw OrderNotFoundException()
        return shipmentRepository.findAllByOrderIdOrderByCreatedAtAsc(requireNotNull(order.id))
            .map { customerResponse(requireNotNull(it.id), order.orderNo) }
    }

    override fun getCustomer(orderNo: String, shipmentNo: String, userId: Long): CustomerShipmentResponse {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, userId) ?: throw OrderNotFoundException()
        val shipment = shipmentRepository.findByShipmentNo(shipmentNo)
            ?.takeIf { it.orderId == order.id }
            ?: throw ShipmentNotFoundException()
        return customerResponse(requireNotNull(shipment.id), order.orderNo)
    }

    override fun trackByTrackingNumber(
        carrierCode: CarrierCode,
        trackingNo: String,
        userId: Long,
    ): CustomerShipmentResponse {
        val carrier = carrierRegistry.find(carrierCode) ?: throw ShipmentNotFoundException()
        val shipment = shipmentRepository.findByCarrierCodeAndTrackingNoNormalized(
            carrierCode,
            carrier.normalizeTrackingNo(trackingNo),
        ) ?: throw ShipmentNotFoundException()
        val order = orderRepository.findById(shipment.orderId).orElse(null) ?: throw ShipmentNotFoundException()
        if (order.customerId != userId && !adminAccessService.isAdmin(userId)) {
            throw ShipmentNotFoundException()
        }
        return customerResponse(requireNotNull(shipment.id), order.orderNo)
    }

    @Transactional
    override fun dispatchShipment(
        shipmentNo: String,
        request: DispatchShipmentRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse {
        adminAccessService.requireAdmin(adminId)
        val requestHash = idempotencyService.requestHash("$shipmentNo|$request")
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val order = orderRepository.lockById(identity.orderId) ?: throw OrderNotFoundException()
        replay(adminId, DISPATCH_SHIPMENT, idempotencyKey, requestHash)?.let {
            return adminResponse(it, order.orderNo)
        }
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id)) ?: throw ShipmentNotFoundException()
        request.note?.let { shipment.note = it }
        ensureDispatchedLocked(order, shipment, clock.instant())
        val shipmentId = requireNotNull(shipment.id)
        idempotencyService.record(adminId, DISPATCH_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        entityManager.flush()
        return adminResponse(shipmentId, order.orderNo)
    }

    @Transactional
    override fun cancelShipment(
        shipmentNo: String,
        request: CancelShipmentRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse {
        adminAccessService.requireAdmin(adminId)
        val requestHash = idempotencyService.requestHash("$shipmentNo|$request")
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val order = orderRepository.lockById(identity.orderId) ?: throw OrderNotFoundException()
        replay(adminId, CANCEL_SHIPMENT, idempotencyKey, requestHash)?.let {
            return adminResponse(it, order.orderNo)
        }
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id)) ?: throw ShipmentNotFoundException()
        if (shipment.status !in setOf(ShipmentStatus.LABEL_PENDING, ShipmentStatus.LABEL_CREATED)) {
            throw ShipmentStatusException("只有未发出的运单可以取消")
        }
        val carrier = carrierRegistry.require(shipment.carrierCode)
        val now = clock.instant()
        shipment.cancelReason = request.reason
        if (carrier.capabilities.remoteLabel) {
            shipment.status = ShipmentStatus.CANCEL_PENDING
            eventPublisher.publishInTx(
                "SHIPMENT",
                requireNotNull(shipment.id),
                "SHIPMENT_CANCEL_REQUESTED",
                "{\"shipmentId\":${shipment.id}}",
            )
        } else {
            shipment.status = ShipmentStatus.CANCELLED
            shipmentItemRepository.releaseAllocatedByShipmentId(
                requireNotNull(shipment.id),
                now,
                request.reason,
            )
            eventPublisher.publishInTx(
                "SHIPMENT",
                requireNotNull(shipment.id),
                "SHIPMENT_CANCELLED",
                "{\"shipmentId\":${shipment.id}}",
            )
        }
        val shipmentId = requireNotNull(shipment.id)
        idempotencyService.record(adminId, CANCEL_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        entityManager.flush()
        return adminResponse(shipmentId, order.orderNo)
    }

    @Transactional
    override fun markManualDelivered(
        shipmentNo: String,
        request: ManualDeliveredRequest,
        adminId: Long,
        idempotencyKey: String,
    ): AdminShipmentResponse {
        adminAccessService.requireAdmin(adminId)
        val requestHash = idempotencyService.requestHash("$shipmentNo|$request")
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val order = orderRepository.lockById(identity.orderId) ?: throw OrderNotFoundException()
        replay(adminId, DELIVER_SHIPMENT, idempotencyKey, requestHash)?.let {
            return adminResponse(it, order.orderNo)
        }
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id)) ?: throw ShipmentNotFoundException()
        if (shipment.carrierCode != CarrierCode.MANUAL) {
            throw ShipmentStatusException("该入口仅支持 MANUAL 运单签收")
        }
        val occurredAt = request.occurredAt ?: clock.instant()
        if (occurredAt.isAfter(clock.instant().plusSeconds(300))) {
            throw ParamErrorException("签收时间不能晚于当前时间")
        }
        val event = TrackingEvent(
            trackingNo = requireNotNull(shipment.trackingNo),
            carrierEventId = "manual:${shipment.id}:${sha256(idempotencyKey).take(32)}",
            statusCode = "MANUAL_DELIVERED",
            normalizedStatus = NormalizedTrackingStatus.DELIVERED,
            location = null,
            description = request.reason,
            occurredAt = occurredAt,
            raw = null,
        )
        applyTrackingEventLocked(order, shipment, event, TrackSource.MANUAL)
        val shipmentId = requireNotNull(shipment.id)
        idempotencyService.record(adminId, DELIVER_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        entityManager.flush()
        return adminResponse(shipmentId, order.orderNo)
    }

    @Transactional
    override fun handleTrackingEvent(carrierCode: CarrierCode, event: TrackingEvent, source: TrackSource) {
        val carrier = carrierRegistry.find(carrierCode) ?: run {
            logger.warn("Ignoring tracking event for unregistered carrier {}", carrierCode)
            return
        }
        val normalizedTrackingNo = carrier.normalizeTrackingNo(event.trackingNo)
        val shipmentIdentity = shipmentRepository.findByCarrierCodeAndTrackingNoNormalized(
            carrierCode,
            normalizedTrackingNo,
        ) ?: run {
            logger.warn(
                "Ignoring tracking event {} for unknown shipment {}:{}",
                event.carrierEventId,
                carrierCode,
                sha256(normalizedTrackingNo).take(16),
            )
            return
        }
        val order = orderRepository.lockById(shipmentIdentity.orderId) ?: run {
            logger.error("Shipment {} references missing order {}", shipmentIdentity.id, shipmentIdentity.orderId)
            return
        }
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(shipmentIdentity.id)) ?: return
        applyTrackingEventLocked(order, shipment, event, source)
    }

    @Transactional
    override fun reconcileOrderDelivery(orderId: Long) {
        val order = orderRepository.lockById(orderId) ?: return
        reconcileOrderDeliveryLocked(order)
    }

    private fun applyTrackingEventLocked(
        order: OrderEntity,
        shipment: Shipment,
        event: TrackingEvent,
        source: TrackSource,
    ) {
        val shipmentId = requireNotNull(shipment.id)
        val inserted = shipmentTrackRepository.insertOnConflictDoNothing(shipmentId, event, source)
        if (!inserted) return
        if (isNewerSummary(shipment, event)) {
            shipment.lastTrackStatus = event.statusCode
            shipment.lastTrackAt = event.occurredAt
            shipment.lastTrackEventId = event.carrierEventId
            shipment.lastTrackLocation = event.location
        }
        if (shipment.status in setOf(ShipmentStatus.CANCEL_PENDING, ShipmentStatus.CANCELLED)) {
            logger.warn(
                "Tracking event {} arrived for shipment {} in terminal cancellation state {}",
                event.carrierEventId,
                shipment.shipmentNo,
                shipment.status,
            )
            return
        }

        when (event.normalizedStatus) {
            NormalizedTrackingStatus.IN_TRANSIT -> ensureDispatchedLocked(order, shipment, event.occurredAt)
            NormalizedTrackingStatus.OUT_FOR_DELIVERY -> {
                val managedShipment = ensureDispatchedLocked(order, shipment, event.occurredAt)
                if (managedShipment.status == ShipmentStatus.IN_TRANSIT) {
                    managedShipment.status = ShipmentStatus.OUT_FOR_DELIVERY
                }
            }
            NormalizedTrackingStatus.DELIVERED -> {
                val managedShipment = ensureDispatchedLocked(order, shipment, event.occurredAt)
                if (managedShipment.status in setOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY)) {
                    managedShipment.status = ShipmentStatus.DELIVERED
                    managedShipment.deliveredAt = event.occurredAt
                    eventPublisher.publishInTx(
                        "SHIPMENT",
                        shipmentId,
                        "SHIPMENT_DELIVERED",
                        "{\"shipmentId\":$shipmentId}",
                    )
                }
                reconcileOrderDeliveryLocked(order)
            }
            NormalizedTrackingStatus.EXCEPTION,
            NormalizedTrackingStatus.UNKNOWN,
            -> Unit
        }
    }

    private fun ensureDispatchedLocked(order: OrderEntity, shipment: Shipment, occurredAt: Instant): Shipment {
        val shipmentId = requireNotNull(shipment.id)
        when (shipment.status) {
            ShipmentStatus.LABEL_CREATED -> {
                shipment.status = ShipmentStatus.IN_TRANSIT
                shipment.shippedAt = occurredAt
                val carrier = carrierRegistry.require(shipment.carrierCode)
                if (carrier.capabilities.polling) {
                    shipment.nextTrackPollAt = clock.instant().plusSeconds(900)
                }
                eventPublisher.publishInTx(
                    "SHIPMENT",
                    shipmentId,
                    "SHIPMENT_DISPATCHED",
                    "{\"shipmentId\":$shipmentId}",
                )
            }
            ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.OUT_FOR_DELIVERY,
            ShipmentStatus.DELIVERED,
            -> Unit
            else -> throw ShipmentStatusException("当前运单不可发出")
        }

        // OrderRepository.markShipped uses clearAutomatically=true. Flush the shipment first,
        // then reload it if the bulk order update detached the current persistence context.
        entityManager.flush()

        when (orderRepository.findStatusById(requireNotNull(order.id))) {
            OrderStatus.PAID -> {
                if (
                    orderRepository.markShipped(
                        requireNotNull(order.id),
                        OrderStatus.PAID,
                        OrderStatus.SHIPPED,
                        occurredAt,
                    ) == 1
                ) {
                    eventPublisher.publishInTx(
                        "ORDER",
                        requireNotNull(order.id),
                        "SHIPPED",
                        "{\"orderId\":${order.id}}",
                    )
                }
            }
            OrderStatus.SHIPPED -> Unit
            else -> throw OrderStatusException("订单状态不允许发出运单")
        }
        return if (entityManager.contains(shipment)) {
            shipment
        } else {
            shipmentRepository.findByIdForUpdate(shipmentId) ?: throw ShipmentNotFoundException()
        }
    }

    private fun reconcileOrderDeliveryLocked(order: OrderEntity) {
        val orderId = requireNotNull(order.id)
        entityManager.flush()
        if (orderRepository.findStatusById(orderId) != OrderStatus.SHIPPED) return
        val summary = fulfillmentQueryRepository.summarize(orderId)
        val fullyAllocated = summary.orderItemCount > 0 &&
            summary.orderItemCount == summary.allocatedDistinctOrderItemCount
        if (!fullyAllocated || summary.nonDeliveredAllocatedShipmentCount != 0L) return
        val deliveredAt = summary.maxAllocatedShipmentDeliveredAt ?: return
        if (
            orderRepository.markDelivered(
                orderId,
                OrderStatus.SHIPPED,
                OrderStatus.DELIVERED,
                deliveredAt,
            ) == 1
        ) {
            eventPublisher.publishInTx("ORDER", orderId, "DELIVERED", "{\"orderId\":$orderId}")
        }
    }

    private fun isNewerSummary(shipment: Shipment, event: TrackingEvent): Boolean {
        val previousAt = shipment.lastTrackAt ?: return true
        if (event.occurredAt != previousAt) return event.occurredAt.isAfter(previousAt)
        return event.carrierEventId > (shipment.lastTrackEventId ?: "")
    }

    private fun replay(
        actorId: Long,
        operation: String,
        key: String,
        requestHash: String,
    ): Long? = idempotencyService.replayShipmentId(actorId, operation, key, requestHash)

    private fun adminResponse(shipmentId: Long, knownOrderNo: String? = null): AdminShipmentResponse {
        val shipment = shipmentRepository.findById(shipmentId).orElse(null) ?: throw ShipmentNotFoundException()
        return AdminShipmentResponse(
            shipment = customerResponse(shipment, knownOrderNo),
            carrierLabelUrl = shipment.carrierLabelUrl,
            createdBy = shipment.createdBy,
            note = shipment.note,
            cancelReason = shipment.cancelReason,
            consecutiveTrackFailures = shipment.consecutiveTrackFailures,
            lastTrackError = shipment.lastTrackError,
        )
    }

    private fun customerResponse(shipmentId: Long, knownOrderNo: String? = null): CustomerShipmentResponse {
        val shipment = shipmentRepository.findById(shipmentId).orElse(null) ?: throw ShipmentNotFoundException()
        return customerResponse(shipment, knownOrderNo)
    }

    private fun customerResponse(shipment: Shipment, knownOrderNo: String? = null): CustomerShipmentResponse {
        val shipmentId = requireNotNull(shipment.id)
        val orderNo = knownOrderNo ?: orderRepository.findOrderNoById(shipment.orderId) ?: throw OrderNotFoundException()
        val items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId).map {
            ShipmentItemResponse(
                orderItemId = it.orderItemId,
                productSnapshot = it.orderItemSnapshot,
                quantity = it.quantity,
                allocationStatus = it.allocationStatus,
            )
        }
        val tracks = shipmentTrackRepository
            .findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId)
            .map {
                ShipmentTrackResponse(
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
        return CustomerShipmentResponse(
            shipmentNo = shipment.shipmentNo,
            orderNo = orderNo,
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val logger = LoggerFactory.getLogger(ShipmentServiceImpl::class.java)
        const val CREATE_SHIPMENT = "CREATE_SHIPMENT"
        const val DISPATCH_SHIPMENT = "DISPATCH_SHIPMENT"
        const val CANCEL_SHIPMENT = "CANCEL_SHIPMENT"
        const val DELIVER_SHIPMENT = "DELIVER_SHIPMENT"
    }
}
