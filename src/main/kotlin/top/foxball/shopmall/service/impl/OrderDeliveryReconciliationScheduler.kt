package top.foxball.shopmall.service.impl

import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.service.ShipmentService

@Component
class OrderDeliveryReconciliationScheduler(
    private val orderRepository: OrderRepository,
    private val shipmentService: ShipmentService,
) {
    @Scheduled(fixedDelayString = "\${shopmall.logistics.reconciliation-delay-ms:60000}")
    fun reconcile() {
        orderRepository.findIdsByStatus(OrderStatus.SHIPPED, PageRequest.of(0, 100))
            .forEach(shipmentService::reconcileOrderDelivery)
    }
}
