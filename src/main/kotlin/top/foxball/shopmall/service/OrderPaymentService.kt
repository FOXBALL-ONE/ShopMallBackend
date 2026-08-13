package top.foxball.shopmall.service

import com.stripe.model.Event
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderPaymentStatus
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
    val paymentStatus: OrderPaymentStatus = OrderPaymentStatus.PENDING_PAYMENT,
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

data class OrderRefundStatusView(
    val orderNo: String,
    val orderStatus: OrderStatus,
    val paymentStatus: OrderPaymentStatus,
    val stripeRefundId: String?,
    val providerRefundStatus: String?,
    val refundAmount: PaymentAmount?,
    val amountMatchesOrder: Boolean?,
)

/**
 * Stripe Checkout 的订单侧适配服务。
 *
 * 负责消费已验签的 Stripe 支付和退款回调，并将远端支付操作交给外盒消费者可靠执行。
 */
interface OrderPaymentService {
    /**
     * 幂等处理 Stripe Checkout 回调；仅接受当前订单流程支持的事件类型。
     * 未关联订单或无法反序列化的有效回调会抛出异常，以便由上层记录并重试。
     */
    fun handleWebhookEvent(event: Event)

    /** 由管理员手动向 Stripe 查询订单的最新收款状态；此操作不修改本地订单状态。 */
    fun queryAdminPaymentStatus(adminId: Long, orderNo: String): AdminOrderPaymentView

    /** 管理端主动查询退款状态；Stripe 已确认成功时同步本地状态。 */
    fun queryAdminRefundStatus(adminId: Long, orderNo: String): OrderRefundStatusView

    /** 客户主动查询退款状态；Stripe 已确认成功时同步本地状态。 */
    fun queryCustomerRefundStatus(customerId: Long, orderNo: String): OrderRefundStatusView

    /** 在申请退款的事务内写入 Stripe 退款外盒事件。 */
    fun requestRefund(order: OrderEntity)

    /** 立即向 Stripe 发起已申请的退款并记录退款标识；外盒消费者也使用此方法进行可靠重试。 */
    fun reconcileRequestedRefund(orderId: Long)

    /** 在当前事务内写入支付补偿外盒事件。 */
    fun cancelOrRefund(order: OrderEntity, reasonKey: String)

    /** 由外盒消费者执行 Checkout 会话失效及必要的退款补偿。 */
    fun reconcileCancellation(orderId: Long)

    /** 由外盒消费者处理本地已取消但 Stripe 已支付的冲突退款。 */
    fun reconcileConflictRefund(orderId: Long)
}
