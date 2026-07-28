package top.foxball.shopmall.service

import com.stripe.model.Event
import top.foxball.shopmall.entity.jdbc.OrderEntity

/**
 * Stripe Checkout 的订单侧适配服务。
 *
 * 负责消费已验签回调并推进本地订单状态，以及将取消和退款等远端操作交给外盒消费者可靠执行。
 */
interface OrderPaymentService {
    /**
     * 幂等处理 Stripe Checkout 回调；仅接受当前订单流程支持的事件类型。
     * 未关联订单或无法反序列化的有效回调会抛出异常，以便由上层记录并重试。
     */
    fun handleWebhookEvent(event: Event)

    /** 在当前事务内写入支付补偿外盒事件。 */
    fun cancelOrRefund(order: OrderEntity, reasonKey: String)

    /** 由外盒消费者执行 Checkout 会话失效及必要的退款补偿。 */
    fun reconcileCancellation(orderId: Long)

    /** 由外盒消费者处理本地已取消但 Stripe 已支付的冲突退款。 */
    fun reconcileConflictRefund(orderId: Long)
}
