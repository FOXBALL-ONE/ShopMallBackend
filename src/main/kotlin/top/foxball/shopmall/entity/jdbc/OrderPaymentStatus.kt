package top.foxball.shopmall.entity.jdbc

/**
 * 订单本地付款生命周期。它与 [OrderStatus] 的履约生命周期独立，避免退款期间将
 * "已经收款"误判为订单已经作废。
 */
enum class OrderPaymentStatus {
    PENDING_PAYMENT,
    PAID,
    REFUNDING,
    PARTIALLY_REFUNDED,
    REFUNDED,
    CANCELLED,
}
