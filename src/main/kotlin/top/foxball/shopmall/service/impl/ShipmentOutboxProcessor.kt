package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.handler.CarrierException
import top.foxball.shopmall.handler.ShipmentNotFoundException
import top.foxball.shopmall.handler.ShipmentStatusException
import top.foxball.shopmall.logistics.CancelLabelRequest
import top.foxball.shopmall.logistics.CancelLabelResult
import top.foxball.shopmall.logistics.CarrierRegistry
import top.foxball.shopmall.logistics.LabelRequest
import top.foxball.shopmall.logistics.ShipmentItemSnapshot
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.service.DomainEventPublisher
import java.time.Clock

@Service
class ShipmentOutboxProcessor(
    private val orderRepository: OrderRepository,
    private val shipmentRepository: ShipmentRepository,
    private val shipmentItemRepository: ShipmentItemRepository,
    private val carrierRegistry: CarrierRegistry,
    private val eventPublisher: DomainEventPublisher,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun handle(shipmentId: Long, eventType: String) {
        when (eventType) {
            "SHIPMENT_LABEL_REQUESTED" -> createRemoteLabel(shipmentId)
            "SHIPMENT_CANCEL_REQUESTED" -> cancelRemoteLabel(shipmentId)
        }
    }

    private fun createRemoteLabel(shipmentId: Long) {
        val task = transactions.execute {
            val shipment = shipmentRepository.findById(shipmentId).orElse(null) ?: return@execute null
            if (shipment.status in setOf(
                    ShipmentStatus.LABEL_CREATED,
                    ShipmentStatus.CANCELLED,
                    ShipmentStatus.DELETED,
                )
            ) {
                return@execute null
            }
            if (shipment.status !in setOf(ShipmentStatus.LABEL_PENDING, ShipmentStatus.CANCEL_PENDING)) {
                throw ShipmentStatusException("当前运单不可创建远程面单")
            }
            val carrier = carrierRegistry.require(shipment.carrierCode)
            if (!carrier.capabilities.remoteLabel) {
                throw CarrierException("该承运商不支持远程面单")
            }
            LabelTask(
                shipmentNo = shipment.shipmentNo,
                carrierCode = shipment.carrierCode,
                requestedTrackingNo = shipment.trackingNo,
                shippingAddress = shipment.shippingAddress.copySnapshot(),
                items = shipmentItemRepository.findAllByShipment_IdOrderById(shipmentId).map {
                    ShipmentItemSnapshot(it.orderItemId, it.orderItemSnapshot, it.quantity)
                },
            )
        } ?: return

        val carrier = carrierRegistry.require(task.carrierCode)
        val response = carrier.createLabel(
            LabelRequest(
                shipmentNo = task.shipmentNo,
                requestedTrackingNo = task.requestedTrackingNo,
                shippingAddress = task.shippingAddress,
                items = task.items,
            ),
        )
        val trackingNo = response.trackingNo.trim().takeIf(String::isNotEmpty)
            ?: throw CarrierException("承运商返回了空 trackingNo")

        transactions.executeWithoutResult {
            val identity = shipmentRepository.findById(shipmentId).orElse(null) ?: return@executeWithoutResult
            orderRepository.lockById(identity.orderId) ?: throw ShipmentNotFoundException()
            val shipment = shipmentRepository.findByIdForUpdate(shipmentId) ?: throw ShipmentNotFoundException()
            if (shipment.status in setOf(
                    ShipmentStatus.LABEL_CREATED,
                    ShipmentStatus.CANCELLED,
                    ShipmentStatus.DELETED,
                )
            ) {
                return@executeWithoutResult
            }
            if (shipment.status !in setOf(ShipmentStatus.LABEL_PENDING, ShipmentStatus.CANCEL_PENDING)) {
                throw ShipmentStatusException("远程面单结果与当前运单状态冲突")
            }
            val wasLabelPending = shipment.status == ShipmentStatus.LABEL_PENDING
            // 条件 UPDATE 只接受 LABEL_PENDING → LABEL_CREATED，回填承运商最终单号与面单信息。
            if (wasLabelPending) {
                shipmentRepository.markLabelCreated(
                    shipmentId,
                    ShipmentStatus.LABEL_PENDING,
                    ShipmentStatus.LABEL_CREATED,
                    trackingNo = trackingNo,
                    normalized = carrier.normalizeTrackingNo(trackingNo),
                    labelUrl = response.labelUrl,
                    trackingUrl = carrier.trackingUrl(trackingNo),
                )
                eventPublisher.publishInTx(
                    "SHIPMENT",
                    shipmentId,
                    "SHIPMENT_LABEL_CREATED",
                    "{\"shipmentId\":$shipmentId}",
                )
            } else {
                // CANCEL_PENDING：面单已成功创建但运单已进入取消流程，仍记录承运商返回的单号，然后立即走取消面单流程，不恢复 LABEL_CREATED。
                shipment.trackingNo = trackingNo
                shipment.trackingNoNormalized = carrier.normalizeTrackingNo(trackingNo)
                shipment.carrierLabelUrl = response.labelUrl
                shipment.trackingUrl = carrier.trackingUrl(trackingNo)
                // 面单回填后补发取消事件，确保即使原 SHIPMENT_CANCEL_REQUESTED 已被 ACK，cancelRemoteLabel 仍会被驱动去作废承运商侧面单并释放行项。
                // cancelRemoteLabel 幂等：仅当 status==CANCEL_PENDING 才推进，重复触发安全。
                eventPublisher.publishInTx(
                    "SHIPMENT",
                    shipmentId,
                    "SHIPMENT_CANCEL_REQUESTED",
                    "{\"shipmentId\":$shipmentId}",
                )
            }
        }
    }

    private fun cancelRemoteLabel(shipmentId: Long) {
        val task = transactions.execute {
            val shipment = shipmentRepository.findById(shipmentId).orElse(null) ?: return@execute null
            if (shipment.status in setOf(ShipmentStatus.CANCELLED, ShipmentStatus.DELETED)) return@execute null
            if (shipment.status != ShipmentStatus.CANCEL_PENDING) {
                throw ShipmentStatusException("当前运单不在远程取消状态")
            }
            val carrier = carrierRegistry.require(shipment.carrierCode)
            if (!carrier.capabilities.remoteLabel) {
                throw CarrierException("该承运商不支持远程面单取消")
            }
            CancelTask(shipment.shipmentNo, shipment.carrierCode, shipment.trackingNo)
        } ?: return

        val carrier = carrierRegistry.require(task.carrierCode)
        when (
            carrier.cancelLabel(
                CancelLabelRequest(
                    shipmentNo = task.shipmentNo,
                    trackingNo = task.trackingNo,
                ),
            )
        ) {
            CancelLabelResult.RETRYABLE_FAILURE -> throw CarrierException("承运商暂时无法取消面单")
            CancelLabelResult.CANCELLED_OR_NOT_FOUND -> Unit
        }

        transactions.executeWithoutResult {
            val identity = shipmentRepository.findById(shipmentId).orElse(null) ?: return@executeWithoutResult
            orderRepository.lockById(identity.orderId) ?: throw ShipmentNotFoundException()
            val shipment = shipmentRepository.findByIdForUpdate(shipmentId) ?: throw ShipmentNotFoundException()
            if (shipment.status in setOf(ShipmentStatus.CANCELLED, ShipmentStatus.DELETED)) {
                return@executeWithoutResult
            }
            if (shipment.status != ShipmentStatus.CANCEL_PENDING) {
                throw ShipmentStatusException("远程取消结果与当前运单状态冲突")
            }
            // 条件 UPDATE：CANCEL_PENDING → CANCELLED；随后在同一事务内释放 ALLOCATED 行项。
            shipmentRepository.markCancelledFromPending(
                shipmentId,
                ShipmentStatus.CANCEL_PENDING,
                ShipmentStatus.CANCELLED,
            )
            val released = shipmentItemRepository.releaseAllocatedByShipmentId(
                shipmentId,
                clock.instant(),
                shipment.cancelReason ?: "REMOTE_LABEL_CANCELLED",
            )
            if (released == 0) {
                throw ShipmentStatusException("远程取消未释放任何运单商品")
            }
            eventPublisher.publishInTx(
                "SHIPMENT",
                shipmentId,
                "SHIPMENT_CANCELLED",
                "{\"shipmentId\":$shipmentId}",
            )
        }
    }

    private data class LabelTask(
        val shipmentNo: String,
        val carrierCode: CarrierCode,
        val requestedTrackingNo: String?,
        val shippingAddress: OrderShippingAddress,
        val items: List<ShipmentItemSnapshot>,
    )

    private data class CancelTask(
        val shipmentNo: String,
        val carrierCode: CarrierCode,
        val trackingNo: String?,
    )
}
