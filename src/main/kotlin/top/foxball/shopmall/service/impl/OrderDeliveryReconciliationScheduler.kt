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
        // 每轮只处理一批(50 条)SHIPPED 订单,避免长时间占用单线程调度线程饿死其他 @Scheduled 任务。
        // 已推进到 DELIVERED 的订单下一轮不再被 findIdsByStatusAfter 返回(status 已变),
        // 故无跨轮游标也不会漏扫;未推进的下一轮仍在批次内。reconcileOrderDelivery 内部幂等。
        orderRepository.findIdsByStatusAfter(OrderStatus.SHIPPED, 0L, PageRequest.of(0, RECONCILE_BATCH_SIZE))
            .forEach(shipmentService::reconcileOrderDelivery)
    }

    private companion object {
        const val RECONCILE_BATCH_SIZE = 50
    }
}
