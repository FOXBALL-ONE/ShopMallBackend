package top.foxball.shopmall.service.impl

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.NormalizedTrackingStatus
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.handler.IdempotencyConflictException
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
import top.foxball.shopmall.service.AdminShipmentQuery
import top.foxball.shopmall.service.DomainEventPublisher
import top.foxball.shopmall.service.ShipmentDetails
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
        carrierCode: CarrierCode,
        trackingNo: String?,
        orderItemIds: List<Long>,
        quantities: List<Int>,
        note: String?,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails {
        adminAccessService.requireAdmin(adminId)
        // 幂等哈希必须对 items 顺序不敏感：客户端重试时若调换行项顺序（语义等价），
        // 原始 toString 会让 hash 变化 → 误判为「同 key+不同请求」返回 409。
        // 故按 orderItemId 稳定排序后再拼接。
        if (orderItemIds.isEmpty() || orderItemIds.size > 50) {
            throw ParamErrorException("运单商品行数量必须在 1 到 50 之间")
        }
        if (orderItemIds.size != quantities.size) {
            throw ParamErrorException("订单商品行与数量必须一一对应")
        }
        if (orderItemIds.any { it < 1 } || quantities.any { it < 1 }) {
            throw ParamErrorException("订单商品行 ID 和数量必须大于 0")
        }
        if (trackingNo != null && trackingNo.length > 64) {
            throw ParamErrorException("物流追踪号不能超过 64 个字符")
        }
        if (note != null && note.length > 200) {
            throw ParamErrorException("运单备注不能超过 200 个字符")
        }
        val normalizedItems = orderItemIds.zip(quantities).sortedBy { it.first }
        val requestHash = idempotencyService.requestHash(
            "$orderNo|$carrierCode|${trackingNo.orEmpty()}|$normalizedItems|${note.orEmpty()}",
        )
        val order = orderRepository.lockByOrderNo(orderNo) ?: throw OrderNotFoundException()
        replay(adminId, CREATE_SHIPMENT, idempotencyKey, requestHash)?.let {
            val shipment = shipmentRepository.findById(it).orElse(null) ?: throw ShipmentNotFoundException()
            return ShipmentDetails(
                shipment = shipment,
                orderNo = order.orderNo,
                items = shipmentItemRepository.findAllByShipment_IdOrderById(it),
                tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(it),
            )
        }
        if (order.status !in setOf(OrderStatus.PAID, OrderStatus.SHIPPED)) {
            throw OrderStatusException("当前订单不可创建运单")
        }
        val orderId = requireNotNull(order.id)
        val requestIds = orderItemIds
        if (requestIds.distinct().size != requestIds.size) {
            throw ParamErrorException("运单商品行不能重复")
        }
        val orderItems = orderItemRepository.findAllByIdForOrder(requestIds, orderId)
        if (orderItems.size != requestIds.size) {
            throw ParamErrorException("运单包含不属于该订单的商品行")
        }
        val requestedQuantities = orderItemIds.zip(quantities).toMap()
        if (orderItems.any { requestedQuantities[it.id] != it.quantity }) {
            throw ParamErrorException("本期仅支持完整分配订单行数量")
        }
        if (shipmentItemRepository.countActiveAllocations(requestIds) > 0) {
            throw ShipmentAllocationConflictException()
        }

        val carrier = carrierRegistry.find(carrierCode)
            ?: throw ParamErrorException("承运商未启用或尚未接入")
        val normalizedTrackingNo = trackingNo?.trim()?.takeIf { it.isNotEmpty() }
        if (!carrier.capabilities.remoteLabel && normalizedTrackingNo == null) {
            throw ParamErrorException("该承运商创建运单时必须提供 trackingNo")
        }
        // remoteLabel 运单的最终 trackingNo 必须由承运商 createLabel 返回，禁止客户端预填：
        // 否则 LABEL_PENDING 运单会带 trackingNoNormalized，使 webhook 能按单号反查到它，
        // 在 outbox 尚未创建面单时就命中 IN_TRANSIT/DELIVERED 轨迹（缺陷3 的竞态入口）。
        if (carrier.capabilities.remoteLabel && normalizedTrackingNo != null) {
            throw ParamErrorException("远程面单承运商的 trackingNo 由系统在面单生成后回填，不可预填")
        }
        val shipment = shipmentRepository.saveAndFlush(
            Shipment(
                shipmentNo = shipmentNoGenerator.next(),
                orderId = orderId,
                carrierCode = carrierCode,
                trackingNo = normalizedTrackingNo,
                trackingNoNormalized = normalizedTrackingNo?.let(carrier::normalizeTrackingNo),
                status = if (carrier.capabilities.remoteLabel) {
                    ShipmentStatus.LABEL_PENDING
                } else {
                    ShipmentStatus.LABEL_CREATED
                },
                shippingAddress = order.shippingAddress.copySnapshot(),
                trackingUrl = normalizedTrackingNo?.let(carrier::trackingUrl),
                createdBy = adminId,
                note = note,
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
        // 并发相同 Idempotency-Key 处理：两个请求被订单行锁串行化后，先到事务的 record 尚未
        // 提交，后到请求的 replay 仍返回 null，两请求都执行 INSERT → 后到者撞 uk_logistics_idempotency
        // 唯一约束 → DataIntegrityViolationException。设计要求「同 key+同请求=原结果」。
        // 在此捕获后重读：若 replay 命中同 key+同 hash 的已落库记录，返回原结果；若 hash 不同
        // (replay 内部抛 IdempotencyConflictException) 或仍无记录，则按真正冲突重新抛出。
        try {
            idempotencyService.record(adminId, CREATE_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        } catch (e: DataIntegrityViolationException) {
            try {
                val prior = replay(adminId, CREATE_SHIPMENT, idempotencyKey, requestHash)
                if (prior != null) {
                    val priorShipment = shipmentRepository.findById(prior).orElse(null)
                        ?: throw ShipmentNotFoundException()
                    return ShipmentDetails(
                        shipment = priorShipment,
                        orderNo = order.orderNo,
                        items = shipmentItemRepository.findAllByShipment_IdOrderById(prior),
                        tracks = shipmentTrackRepository
                            .findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(prior),
                    )
                }
            } catch (conflict: IdempotencyConflictException) {
                throw conflict
            }
            throw e
        }
        entityManager.flush()
        val created = shipmentRepository.findById(shipmentId).orElse(null) ?: throw ShipmentNotFoundException()
        return ShipmentDetails(
            shipment = created,
            orderNo = order.orderNo,
            items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
            tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
        )
    }

    override fun listAdmin(orderNo: String, adminId: Long): List<ShipmentDetails> {
        adminAccessService.requireAdmin(adminId)
        val order = orderRepository.findByOrderNo(orderNo) ?: throw OrderNotFoundException()
        return shipmentRepository.findAllByOrderIdOrderByCreatedAtAsc(requireNotNull(order.id))
            .map { shipment ->
                val shipmentId = requireNotNull(shipment.id)
                ShipmentDetails(
                    shipment = shipment,
                    orderNo = order.orderNo,
                    items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
                    tracks = shipmentTrackRepository
                        .findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
                )
            }
    }

    override fun listAdmin(adminId: Long, query: AdminShipmentQuery): Page<ShipmentDetails> {
        adminAccessService.requireAdmin(adminId)
        if (query.page < 0 || query.size !in 1..100) {
            throw ParamErrorException("分页参数无效")
        }
        val orderNo = query.orderNo?.trim()?.takeIf(String::isNotEmpty)
        val trackingNo = query.trackingNo?.trim()?.takeIf(String::isNotEmpty)
        val shipments = shipmentRepository.findAllForAdmin(
            status = query.status,
            deleted = ShipmentStatus.DELETED,
            carrier = query.carrier,
            orderNo = orderNo,
            trackingNo = trackingNo,
            hasError = query.hasError,
            pageable = PageRequest.of(query.page, query.size),
        )
        if (shipments.isEmpty) {
            return PageImpl(emptyList(), shipments.pageable, shipments.totalElements)
        }

        val shipmentIds = shipments.content.map { requireNotNull(it.id) }
        val orderNosById = orderRepository.findAllById(shipments.content.map(Shipment::orderId).distinct())
            .associate { requireNotNull(it.id) to it.orderNo }
        val itemsByShipmentId = shipmentItemRepository
            .findAllByShipment_IdInOrderByShipment_IdAscIdAsc(shipmentIds)
            .groupBy { requireNotNull(it.shipment?.id) }
        val tracksByShipmentId = shipmentTrackRepository
            .findAllByShipment_IdInOrderByShipment_IdAscOccurredAtAscCarrierEventIdAsc(shipmentIds)
            .groupBy { requireNotNull(it.shipment?.id) }
        val details = shipments.content.map { shipment ->
            val shipmentId = requireNotNull(shipment.id)
            ShipmentDetails(
                shipment = shipment,
                orderNo = orderNosById[shipment.orderId] ?: throw OrderNotFoundException(),
                items = itemsByShipmentId[shipmentId].orEmpty(),
                tracks = tracksByShipmentId[shipmentId].orEmpty(),
            )
        }
        return PageImpl(details, shipments.pageable, shipments.totalElements)
    }

    override fun getAdmin(shipmentNo: String, adminId: Long): ShipmentDetails {
        adminAccessService.requireAdmin(adminId)
        val shipment = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val order = orderRepository.findById(shipment.orderId).orElse(null) ?: throw OrderNotFoundException()
        val shipmentId = requireNotNull(shipment.id)
        return ShipmentDetails(
            shipment = shipment,
            orderNo = order.orderNo,
            items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
            tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
        )
    }

    @Transactional
    override fun deleteShipment(shipmentNo: String, adminId: Long): Shipment {
        adminAccessService.requireAdmin(adminId)
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id))
            ?: throw ShipmentNotFoundException()
        if (shipment.status == ShipmentStatus.DELETED) {
            return shipment
        }
        if (shipment.status !in setOf(ShipmentStatus.CANCELLED, ShipmentStatus.DELIVERED)) {
            throw ShipmentStatusException("运单需先取消或完成签收才能删除")
        }

        val shipmentId = requireNotNull(shipment.id)
        shipment.status = ShipmentStatus.DELETED
        shipment.nextTrackPollAt = null
        shipment.pollLeaseOwner = null
        shipment.pollLeaseUntil = null
        shipmentRepository.saveAndFlush(shipment)
        shipmentItemRepository.releaseAllocatedByShipmentId(
            shipmentId = shipmentId,
            at = clock.instant(),
            reason = "SHIPMENT_DELETED",
        )
        entityManager.clear()
        return shipmentRepository.findById(shipmentId).orElse(null) ?: throw ShipmentNotFoundException()
    }

    @Transactional
    override fun permanentlyDeleteShipment(shipmentNo: String, adminId: Long) {
        adminAccessService.requireAdmin(adminId)
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id))
            ?: throw ShipmentNotFoundException()
        if (shipment.status != ShipmentStatus.DELETED) {
            throw ShipmentStatusException("只有已逻辑删除的运单才能永久删除")
        }

        val shipmentId = requireNotNull(shipment.id)
        shipmentTrackRepository.deleteAllByShipmentId(shipmentId)
        shipmentItemRepository.deleteAllByShipmentId(shipmentId)
        entityManager.clear()
        if (shipmentRepository.deleteByIdAndStatus(shipmentId, ShipmentStatus.DELETED) != 1) {
            throw ShipmentStatusException("只有已逻辑删除的运单才能永久删除")
        }
    }

    override fun listCustomer(orderNo: String, userId: Long): List<ShipmentDetails> {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, userId) ?: throw OrderNotFoundException()
        return shipmentRepository.findAllByOrderIdAndStatusNotOrderByCreatedAtAsc(
            requireNotNull(order.id),
            ShipmentStatus.DELETED,
        )
            .map { shipment ->
                val shipmentId = requireNotNull(shipment.id)
                ShipmentDetails(
                    shipment = shipment,
                    orderNo = order.orderNo,
                    items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
                    tracks = shipmentTrackRepository
                        .findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
                )
            }
    }

    override fun getCustomer(orderNo: String, shipmentNo: String, userId: Long): ShipmentDetails {
        val order = orderRepository.findByOrderNoAndCustomerId(orderNo, userId) ?: throw OrderNotFoundException()
        val shipment = shipmentRepository.findByShipmentNoAndStatusNot(shipmentNo, ShipmentStatus.DELETED)
            ?.takeIf { it.orderId == order.id }
            ?: throw ShipmentNotFoundException()
        val shipmentId = requireNotNull(shipment.id)
        return ShipmentDetails(
            shipment = shipment,
            orderNo = order.orderNo,
            items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
            tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
        )
    }

    override fun trackByTrackingNumber(
        carrierCode: CarrierCode,
        trackingNo: String,
        userId: Long,
    ): ShipmentDetails {
        val carrier = carrierRegistry.find(carrierCode) ?: throw ShipmentNotFoundException()
        val shipment = shipmentRepository.findByCarrierCodeAndTrackingNoNormalizedAndStatusNot(
            carrierCode,
            carrier.normalizeTrackingNo(trackingNo),
            ShipmentStatus.DELETED,
        ) ?: throw ShipmentNotFoundException()
        val order = orderRepository.findById(shipment.orderId).orElse(null) ?: throw ShipmentNotFoundException()
        if (order.customerId != userId && !adminAccessService.isAdmin(userId)) {
            throw ShipmentNotFoundException()
        }
        val shipmentId = requireNotNull(shipment.id)
        return ShipmentDetails(
            shipment = shipment,
            orderNo = order.orderNo,
            items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
            tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
        )
    }

    @Transactional
    override fun dispatchShipment(
        shipmentNo: String,
        note: String?,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails {
        adminAccessService.requireAdmin(adminId)
        if (note != null && note.length > 200) {
            throw ParamErrorException("运单备注不能超过 200 个字符")
        }
        val requestHash = idempotencyService.requestHash("$shipmentNo|${note.orEmpty()}")
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val order = orderRepository.lockById(identity.orderId) ?: throw OrderNotFoundException()
        replay(adminId, DISPATCH_SHIPMENT, idempotencyKey, requestHash)?.let {
            val replayed = shipmentRepository.findById(it).orElse(null) ?: throw ShipmentNotFoundException()
            return ShipmentDetails(
                shipment = replayed,
                orderNo = order.orderNo,
                items = shipmentItemRepository.findAllByShipment_IdOrderById(it),
                tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(it),
            )
        }
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id)) ?: throw ShipmentNotFoundException()
        note?.let { shipment.note = it }
        ensureDispatchedLocked(order, shipment, clock.instant())
        val shipmentId = requireNotNull(shipment.id)
        idempotencyService.record(adminId, DISPATCH_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        entityManager.flush()
        val dispatched = shipmentRepository.findById(shipmentId).orElse(null) ?: throw ShipmentNotFoundException()
        return ShipmentDetails(
            shipment = dispatched,
            orderNo = order.orderNo,
            items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
            tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
        )
    }

    @Transactional
    override fun cancelShipment(
        shipmentNo: String,
        reason: String,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails {
        adminAccessService.requireAdmin(adminId)
        if (reason.isBlank() || reason.length > 200) {
            throw ParamErrorException("取消原因不能为空且不能超过 200 个字符")
        }
        val requestHash = idempotencyService.requestHash("$shipmentNo|$reason")
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val order = orderRepository.lockById(identity.orderId) ?: throw OrderNotFoundException()
        replay(adminId, CANCEL_SHIPMENT, idempotencyKey, requestHash)?.let {
            val replayed = shipmentRepository.findById(it).orElse(null) ?: throw ShipmentNotFoundException()
            return ShipmentDetails(
                shipment = replayed,
                orderNo = order.orderNo,
                items = shipmentItemRepository.findAllByShipment_IdOrderById(it),
                tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(it),
            )
        }
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id)) ?: throw ShipmentNotFoundException()
        if (shipment.status !in setOf(ShipmentStatus.LABEL_PENDING, ShipmentStatus.LABEL_CREATED)) {
            throw ShipmentStatusException("只有未发出的运单可以取消")
        }
        val carrier = carrierRegistry.require(shipment.carrierCode)
        val now = clock.instant()
        val shipmentId = requireNotNull(shipment.id)
        if (carrier.capabilities.remoteLabel) {
            shipmentRepository.markCancelPending(
                shipmentId,
                listOf(ShipmentStatus.LABEL_PENDING, ShipmentStatus.LABEL_CREATED),
                ShipmentStatus.CANCEL_PENDING,
                reason,
            )
            eventPublisher.publishInTx(
                "SHIPMENT",
                shipmentId,
                "SHIPMENT_CANCEL_REQUESTED",
                "{\"shipmentId\":$shipmentId}",
            )
        } else {
            shipmentRepository.markCancelledImmediate(
                shipmentId,
                listOf(ShipmentStatus.LABEL_PENDING, ShipmentStatus.LABEL_CREATED),
                ShipmentStatus.CANCELLED,
                reason,
            )
            shipmentItemRepository.releaseAllocatedByShipmentId(
                shipmentId,
                now,
                reason,
            )
            eventPublisher.publishInTx(
                "SHIPMENT",
                shipmentId,
                "SHIPMENT_CANCELLED",
                "{\"shipmentId\":$shipmentId}",
            )
        }
        idempotencyService.record(adminId, CANCEL_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        entityManager.flush()
        val cancelled = shipmentRepository.findById(shipmentId).orElse(null) ?: throw ShipmentNotFoundException()
        return ShipmentDetails(
            shipment = cancelled,
            orderNo = order.orderNo,
            items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
            tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
        )
    }

    @Transactional
    override fun markManualDelivered(
        shipmentNo: String,
        occurredAt: Instant?,
        reason: String,
        adminId: Long,
        idempotencyKey: String,
    ): ShipmentDetails {
        adminAccessService.requireAdmin(adminId)
        if (reason.isBlank() || reason.length > 200) {
            throw ParamErrorException("签收原因不能为空且不能超过 200 个字符")
        }
        val requestHash = idempotencyService.requestHash("$shipmentNo|${occurredAt ?: ""}|$reason")
        val identity = shipmentRepository.findByShipmentNo(shipmentNo) ?: throw ShipmentNotFoundException()
        val order = orderRepository.lockById(identity.orderId) ?: throw OrderNotFoundException()
        replay(adminId, DELIVER_SHIPMENT, idempotencyKey, requestHash)?.let {
            val replayed = shipmentRepository.findById(it).orElse(null) ?: throw ShipmentNotFoundException()
            return ShipmentDetails(
                shipment = replayed,
                orderNo = order.orderNo,
                items = shipmentItemRepository.findAllByShipment_IdOrderById(it),
                tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(it),
            )
        }
        val shipment = shipmentRepository.findByIdForUpdate(requireNotNull(identity.id)) ?: throw ShipmentNotFoundException()
        if (shipment.carrierCode != CarrierCode.MANUAL) {
            throw ShipmentStatusException("该入口仅支持 MANUAL 运单签收")
        }
        // 终态校验：MANUAL 运单已签收/取消/取消中时拒绝重入，避免不同 Idempotency-Key 再次调
        // delivered 时因 carrierEventId 不同而插入重复 MANUAL_DELIVERED 轨迹并触发整单重放。
        when (shipment.status) {
            ShipmentStatus.DELIVERED -> throw ShipmentStatusException("运单已签收，不可重复签收")
            ShipmentStatus.CANCEL_PENDING -> throw ShipmentStatusException("运单取消中，不可签收")
            ShipmentStatus.CANCELLED -> throw ShipmentStatusException("运单已取消，不可签收")
            else -> Unit
        }
        val deliveredAt = occurredAt ?: clock.instant()
        if (deliveredAt.isAfter(clock.instant().plusSeconds(300))) {
            throw ParamErrorException("签收时间不能晚于当前时间")
        }
        // 下界校验：签收时间不得早于运单发出时间，防止伪造历史时间注入。
        // 正常流程 applyTrackingEventLocked 会先 ensureDispatchedLocked 写 shippedAt，
        // 理论上此处 shippedAt 必非空；若因异常路径缺失则不阻塞签收。
        if (shipment.shippedAt != null && deliveredAt.isBefore(shipment.shippedAt)) {
            throw ParamErrorException("签收时间不能早于运单发出时间")
        }
        val event = TrackingEvent(
            trackingNo = requireNotNull(shipment.trackingNo),
            carrierEventId = "manual:${shipment.id}:${sha256(idempotencyKey).take(32)}",
            statusCode = "MANUAL_DELIVERED",
            normalizedStatus = NormalizedTrackingStatus.DELIVERED,
            location = null,
            description = reason,
            occurredAt = deliveredAt,
            raw = null,
        )
        applyTrackingEventLocked(order, shipment, event, TrackSource.MANUAL)
        val shipmentId = requireNotNull(shipment.id)
        idempotencyService.record(adminId, DELIVER_SHIPMENT, idempotencyKey, requestHash, shipmentId)
        entityManager.flush()
        val delivered = shipmentRepository.findById(shipmentId).orElse(null) ?: throw ShipmentNotFoundException()
        return ShipmentDetails(
            shipment = delivered,
            orderNo = order.orderNo,
            items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId),
            tracks = shipmentTrackRepository.findAllByShipment_IdOrderByOccurredAtAscCarrierEventIdAsc(shipmentId),
        )
    }

    @Transactional
    override fun handleTrackingEvent(carrierCode: CarrierCode, event: TrackingEvent, source: TrackSource) {
        val carrier = carrierRegistry.find(carrierCode) ?: run {
            logger.warn("Ignoring tracking event for unregistered carrier {}", carrierCode)
            return
        }
        val normalizedTrackingNo = carrier.normalizeTrackingNo(event.trackingNo)
        val shipmentIdentity = shipmentRepository.findByCarrierCodeAndTrackingNoNormalizedAndStatusNot(
            carrierCode,
            normalizedTrackingNo,
            ShipmentStatus.DELETED,
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
        // 摘要覆盖走条件 UPDATE，避免乱序旧事件回退 lastTrackAt/status。
        shipmentRepository.updateLastTrackIfNewer(
            shipmentId,
            event.statusCode,
            event.occurredAt,
            event.carrierEventId,
            event.location,
        )

        // CANCEL_PENDING/CANCELLED 是终态，后到轨迹只留痕告警，不复活。
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
                ensureDispatchedLocked(order, shipment, event.occurredAt)
                shipmentRepository.markOutForDelivery(
                    shipmentId,
                    ShipmentStatus.IN_TRANSIT,
                    ShipmentStatus.OUT_FOR_DELIVERY,
                )
            }
            NormalizedTrackingStatus.DELIVERED -> {
                ensureDispatchedLocked(order, shipment, event.occurredAt)
                val changed = shipmentRepository.markDelivered(
                    shipmentId,
                    listOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY),
                    ShipmentStatus.DELIVERED,
                    at = event.occurredAt,
                )
                if (changed == 1) {
                    eventPublisher.publishInTx(
                        "SHIPMENT",
                        shipmentId,
                        "SHIPMENT_DELIVERED",
                        "{\"shipmentId\":$shipmentId}",
                    )
                }
                // 即使运单此前已 DELIVERED，也要执行聚合，修复历史事务在整单推进前失败的情况。
                reconcileOrderDeliveryLocked(order)
            }
            NormalizedTrackingStatus.EXCEPTION,
            NormalizedTrackingStatus.UNKNOWN,
            -> Unit
        }
    }

    private fun ensureDispatchedLocked(order: OrderEntity, shipment: Shipment, occurredAt: Instant): Shipment {
        val shipmentId = requireNotNull(shipment.id)
        if (shipment.status == ShipmentStatus.LABEL_CREATED) {
            // 条件 UPDATE 只接受 LABEL_CREATED → IN_TRANSIT，重复在途事件返回 0 行。
            val changed = shipmentRepository.markInTransit(
                shipmentId,
                ShipmentStatus.LABEL_CREATED,
                ShipmentStatus.IN_TRANSIT,
                occurredAt,
            )
            if (changed == 1) {
                val carrier = carrierRegistry.require(shipment.carrierCode)
                if (carrier.capabilities.polling) {
                    shipmentRepository.scheduleNextPoll(shipmentId, clock.instant().plusSeconds(900))
                }
                eventPublisher.publishInTx(
                    "SHIPMENT",
                    shipmentId,
                    "SHIPMENT_DISPATCHED",
                    "{\"shipmentId\":$shipmentId}",
                )
            }
        } else if (shipment.status == ShipmentStatus.LABEL_PENDING) {
            // 设计文档 §3.4 要求承运商在远程面单 outbox 尚未处理(LABEL_PENDING)时直接回传
            // IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED 轨迹应先补做 LABEL_CREATED → IN_TRANSIT。
            // 但实际面单创建(承运商下单、回填 final trackingNo/carrierLabelUrl、发布
            // SHIPMENT_LABEL_CREATED)的权威幂等点是 ShipmentOutboxProcessor.createRemoteLabel，
            // 此处无法干净地复制其全部副作用(labelUrl 缺失、承运商下单 API 未调用、outbox 仍会
            // 触发但 wasLabelPending 已为 false → 永远拿不到真实面单)。
            //
            // 命中分析：webhook 反查依赖 findByCarrierCodeAndTrackingNoNormalized，要求运单
            // trackingNoNormalized 非空；remoteLabel 运单创建时 trackingNo 可由客户端提供
            // (createShipment 未禁止 remoteLabel + 客户端 trackingNo)，故 trackingNoNormalized
            // 可非空 → webhook 路径理论上可命中 LABEL_PENDING。POLL 路径仅轮询已 IN_TRANSIT
            // 运单(scheduleNextPoll 只在 LABEL_CREATED → IN_TRANSIT 后登记)，不会命中
            // LABEL_PENDING。即本分支主要出现在「remoteLabel 运单携带客户端 trackingNo + 承运商
            // 在 outbox 处理前先回传轨迹」的竞态。对此场景静默推进会丢面单语义，故给出明确错误，
            // 让 webhook 事务回滚、轨迹依赖 (carrierEventId 唯一) 由后续 outbox 推进到 LABEL_CREATED
            // 后的事件重放兜底，而非在此伪造状态。
            throw ShipmentStatusException("运单尚在 LABEL_PENDING 状态，等待面单生成后再接收在途/签收轨迹")
        } else if (shipment.status !in setOf(
                ShipmentStatus.IN_TRANSIT,
                ShipmentStatus.OUT_FOR_DELIVERY,
                ShipmentStatus.DELIVERED,
            )
        ) {
            throw ShipmentStatusException("当前运单不可发出")
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

    private fun replay(
        actorId: Long,
        operation: String,
        key: String,
        requestHash: String,
    ): Long? = idempotencyService.replayShipmentId(actorId, operation, key, requestHash)

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
