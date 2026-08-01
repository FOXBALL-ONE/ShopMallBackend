package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.OrderStatus
import java.time.Instant

/** 面向订单页面的 Stripe Checkout 会话操作。 */
interface OrderCheckoutService {
    /**
     * 创建或复用当前订单的托管支付会话。
     *
     * @param idempotencyKey 下单时使用的服务端签发幂等键；必须与该订单的持久绑定一致，否则拒绝。
     */
    fun openCheckout(customerId: Long, orderNo: String, idempotencyKey: String): OrderCheckoutView
}

/** 返回给前端整页跳转所需的最小支付信息。 */
data class OrderCheckoutView(
    val orderNo: String,
    val status: OrderStatus,
    val checkoutUrl: String,
    val expiresAt: Instant,
)
