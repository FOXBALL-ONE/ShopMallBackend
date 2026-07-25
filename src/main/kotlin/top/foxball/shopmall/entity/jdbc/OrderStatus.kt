package top.foxball.shopmall.entity.jdbc

enum class OrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    COMPLETED,
    CANCELLED,
}
