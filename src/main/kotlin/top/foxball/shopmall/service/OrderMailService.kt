package top.foxball.shopmall.service

import top.foxball.shopmall.service.payMent.PaymentRefund

/** 订单付款成功后的客户确认邮件服务。 */
interface OrderMailService {
    /** 根据订单聚合数据向客户发送付款成功确认邮件；Stripe 未提供托管收据时不展示收据入口。 */
    fun sendPaymentConfirmation(orderId: Long, stripeReceiptUrl: String?)

    /** Stripe 已接受退款请求后，向客户发送包含订单及退款信息的通知。 */
    fun sendRefundRequested(orderId: Long, refund: PaymentRefund)

    /** Stripe 异步确认退款状态后，向客户发送退款确认通知。 */
    fun sendRefundConfirmation(orderId: Long, refund: PaymentRefund)
}
