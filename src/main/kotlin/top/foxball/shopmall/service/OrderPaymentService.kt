package top.foxball.shopmall.service

import com.stripe.model.Event
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentProviderId
import top.foxball.shopmall.service.payMent.PaymentStatus

enum class AdminOrderPaymentQuerySource {
    PAYMENT_INTENT,
    CHECKOUT_SESSION,
}

data class AdminOrderPaymentView(
    val orderNo: String,
    val orderStatus: OrderStatus,
    val provider: PaymentProviderId,
    val providerStatus: PaymentStatus,
    val querySource: AdminOrderPaymentQuerySource,
    val paymentIntentId: String?,
    val checkoutSessionId: String?,
    val paymentIntentStatus: String?,
    val checkoutSessionStatus: String?,
    val checkoutPaymentStatus: String?,
    val amount: PaymentAmount?,
    val amountMatchesOrder: Boolean?,
    val failureCode: String?,
    val failureMessage: String?,
)

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

    /** 由管理员手动向 Stripe 查询订单的最新收款状态；此操作不修改本地订单状态。 */
    fun queryAdminPaymentStatus(adminId: Long, orderNo: String): AdminOrderPaymentView

    /** 在当前事务内写入支付补偿外盒事件。 */
    fun cancelOrRefund(order: OrderEntity, reasonKey: String)

    /** 由外盒消费者执行 Checkout 会话失效及必要的退款补偿。 */
    fun reconcileCancellation(orderId: Long)

    /** 由外盒消费者处理本地已取消但 Stripe 已支付的冲突退款。 */
    fun reconcileConflictRefund(orderId: Long)
}
