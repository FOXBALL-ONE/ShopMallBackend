package top.foxball.shopmall.entity.jdbc

enum class OrderStatus {
    /** 等待用户付款。 */
    PENDING_PAYMENT,
    /** 已完成付款，等待后续处理。 */
    PAID,
    /** 退款处理中。 */
    REFUNDING,
    /** 已完成退款。 */
    REFUNDED,
    /** 商品已发货。 */
    SHIPPED,
    /** 商品已送达。 */
    DELIVERED,
    /** 订单已完成。 */
    COMPLETED,
    /** 订单已取消。 */
    CANCELLED,
    /** 订单已逻辑删除。 */
    DELETED,
}
