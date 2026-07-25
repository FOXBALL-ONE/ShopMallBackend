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
        orderRepository.findExpiredPendingIds(clock.instant(), pageable = PageRequest.of(0, 100))
            .forEach { orderId ->
                try {
                    processor.cancelExpired(orderId)
                } catch (ex: Exception) {
                    logger.error("Failed to cancel expired order {}", orderId, ex)
                }
            }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OrderTimeoutScheduler::class.java)
    }
}
