package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.OrderStatus
import java.time.Instant

/** 面向订单页面的 Stripe Checkout 会话操作。 */
interface OrderCheckoutService {
    /**
     * 为当前客户的待支付订单创建或复用托管支付会话。
     */
    fun openCheckout(customerId: Long, orderNo: String): OrderCheckoutView
}

/** 返回给前端整页跳转所需的最小支付信息。 */
data class OrderCheckoutView(
    val orderNo: String,
    val status: OrderStatus,
    val checkoutUrl: String,
    val expiresAt: Instant,
)
