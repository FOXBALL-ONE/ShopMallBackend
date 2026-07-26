package top.foxball.shopmall.service.impl

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.repository.OrderRepository
import java.time.Clock

@Component
class OrderTimeoutScheduler(
    private val orderRepository: OrderRepository,
    private val processor: OrderTimeoutProcessor,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${shopmall.order.timeout-scan-delay-ms:60000}")
    fun cancelExpiredOrders() {
        // 每轮只处理一批过期订单,避免长时间占用单线程调度线程饿死其他 @Scheduled 任务。
        // 已取消的订单下一轮不再被 findExpiredPendingIds 返回(status 已变 CANCELLED),
        // 故无跨轮游标也不会漏扫;积压靠多轮 fixedDelay 追赶。cancelExpired 内部 markCancelled 门控幂等。
        orderRepository.findExpiredPendingIds(clock.instant(), pageable = PageRequest.of(0, TIMEOUT_BATCH_SIZE))
            .forEach { orderId ->
                try {
                    processor.cancelExpired(orderId)
                } catch (ex: Exception) {
                    logger.error("Failed to cancel expired order {}", orderId, ex)
                }
            }
    }

    private companion object {
        const val TIMEOUT_BATCH_SIZE = 50

        val logger = LoggerFactory.getLogger(OrderTimeoutScheduler::class.java)
    }
}
