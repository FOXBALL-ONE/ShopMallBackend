package top.foxball.shopmall.service

/** 订单付款成功后的客户确认邮件服务。 */
interface OrderMailService {
    /** 根据订单聚合数据向客户发送付款成功确认邮件；Stripe 未提供托管收据时不展示收据入口。 */
    fun sendPaymentConfirmation(orderId: Long, stripeReceiptUrl: String?)
}
