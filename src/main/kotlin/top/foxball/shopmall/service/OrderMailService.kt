package top.foxball.shopmall.service

/** 订单付款成功后的客户确认邮件服务。 */
interface OrderMailService {
    /** 根据订单聚合数据和 Stripe 托管收据链接向客户发送付款成功确认邮件。 */
    fun sendPaymentConfirmation(orderId: Long, stripeReceiptUrl: String)
}
