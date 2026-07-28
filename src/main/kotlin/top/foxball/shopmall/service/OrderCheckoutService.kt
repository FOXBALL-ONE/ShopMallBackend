package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.OrderStatus
import java.time.Instant

/** 面向订单页面的 Stripe Checkout 会话操作。 */
interface OrderCheckoutService {
    /** 创建或复用当前订单的托管支付会话。 */
    fun openCheckout(customerId: Long, orderNo: String): OrderCheckoutView
}

/** 返回给前端整页跳转所需的最小支付信息。 */
data class OrderCheckoutView(
    val orderNo: String,
    val status: OrderStatus,
    val checkoutUrl: String,
    val expiresAt: Instant,
)
