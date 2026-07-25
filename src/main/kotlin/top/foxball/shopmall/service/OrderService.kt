package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderStatus
import java.time.Instant
import java.util.UUID

data class OrderLineCommand(
    val productId: Long,
    val quantity: Int,
)

data class PlaceOrderCommand(
    val items: List<OrderLineCommand>,
    val addressId: UUID,
    val clientMessage: String? = null,
)

data class OrderPageQuery(
    val page: Int = 0,
    val size: Int = 20,
)

data class AdminOrderQuery(
    val page: Int = 0,
    val size: Int = 20,
    val status: OrderStatus? = null,
    val customerId: Long? = null,
    val orderNo: String? = null,
)

data class OrderView(
    val order: OrderEntity,
    val items: List<OrderItem>,
    val clientSecret: String? = null,
)

data class OrderPaymentView(
    val orderNo: String,
    val status: OrderStatus,
    val clientSecret: String?,
    val expiresAt: Instant?,
)

interface OrderService {
    fun placeOrder(
        customerId: Long,
        command: PlaceOrderCommand,
        idempotencyKey: String? = null,
    ): OrderView

    fun listCustomer(customerId: Long, query: OrderPageQuery): Page<OrderView>

    fun getCustomer(customerId: Long, orderNo: String): OrderView

    fun getPayment(customerId: Long, orderNo: String): OrderPaymentView

    fun cancel(customerId: Long, orderNo: String, reason: String): OrderView

    fun listAdmin(adminId: Long, query: AdminOrderQuery): Page<OrderView>

    fun refund(adminId: Long, orderNo: String, reason: String): OrderView
}
