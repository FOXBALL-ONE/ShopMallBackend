package top.foxball.shopmall.entity.jdbc

enum class OrderStatus {
    PENDING_PAYMENT,
    PAID,
    REFUNDING,
    REFUNDED,
    SHIPPED,
    DELIVERED,
    COMPLETED,
    CANCELLED,
    DELETED,
}
