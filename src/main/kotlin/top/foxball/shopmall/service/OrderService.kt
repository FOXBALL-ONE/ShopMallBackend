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
)

data class OrderPaymentView(
    val orderNo: String,
    val status: OrderStatus,
    val checkoutSessionId: String?,
    val expiresAt: Instant?,
)

/**
 * 订单生命周期服务，负责下单、查询、取消和退款等本地订单状态变更。
 *
 * 支付会话的创建由 [OrderCheckoutService] 负责，第三方支付回调及补偿由 [OrderPaymentService] 负责。
 */
interface OrderService {
    /**
     * 根据服务端商品与地址快照创建待支付订单，并使用服务端签发的幂等键防止重复扣减库存。
     * 幂等键必须由 [top.foxball.shopmall.shared.OrderIdempotencyKeyService] 提前签发并属于当前用户。
     */
    fun placeOrder(
        customerId: Long,
        command: PlaceOrderCommand,
        idempotencyKey: String,
    ): OrderView

    /** 按分页条件返回当前客户可见的订单。 */
    fun listCustomer(customerId: Long, query: OrderPageQuery): Page<OrderView>

    /** 查询当前客户名下的订单及其订单明细；订单不存在或不属于该客户时抛出异常。 */
    fun getCustomer(customerId: Long, orderNo: String): OrderView

    /** 返回当前客户订单的支付会话标识和失效时间，不创建新的支付会话。 */
    fun getPayment(customerId: Long, orderNo: String): OrderPaymentView

    /** 取消当前客户仍可取消的订单，恢复库存并登记后续支付补偿任务。 */
    fun cancel(customerId: Long, orderNo: String, reason: String): OrderView

    /** 以管理员权限按筛选条件分页查询订单。 */
    fun listAdmin(adminId: Long, query: AdminOrderQuery): Page<OrderView>

    /** 由管理员取消可退款订单、恢复库存，并登记第三方支付退款补偿任务。 */
    fun refund(adminId: Long, orderNo: String, reason: String): OrderView
}
