package top.foxball.shopmall.service

/** 订单付款成功后的客户确认邮件服务。 */
interface OrderMailService {
    /** 根据订单聚合数据向客户发送付款成功确认邮件。 */
    fun sendPaymentConfirmation(orderId: Long)
}
