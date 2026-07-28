package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Instant

/**
 * 商城订单聚合根，对应一次从待支付到完成或取消的交易流程。
 *
 * 订单保存下单时的金额和收货地址快照，以及支付、取消、发货和签收等关键时间点；订单明细
 * 由 [OrderItem] 持有外键，避免在聚合根中维护可能被延迟加载的明细集合。
 */
@Entity
@Table(
    name = "orders",
    indexes = [
        Index(name = "idx_orders_no", columnList = "order_no"),
        Index(name = "idx_orders_customer_status", columnList = "customer_id,status"),
        Index(name = "idx_orders_status_timeout", columnList = "status,expires_at"),
    ],
)
class OrderEntity(
    /** 订单的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 面向商户和客户展示的唯一订单号。 */
    @field:Size(max = 32)
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    var orderNo: String = "",

    /** 下单客户的用户标识。 */
    @field:Min(1)
    @Column(name = "customer_id", nullable = false)
    var customerId: Long = 0,

    /** 订单当前所处的业务生命周期状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: OrderStatus = OrderStatus.PENDING_PAYMENT,

    /** 商品明细的成交金额小计，未包含运费、税费和优惠。 */
    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "items_subtotal", nullable = false, precision = 12, scale = 2)
    var itemsSubtotal: BigDecimal = BigDecimal.ZERO,

    /** 本订单向客户收取的运费。 */
    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "shipping_fee", nullable = false, precision = 12, scale = 2)
    var shippingFee: BigDecimal = BigDecimal.ZERO,

    /** 本订单向客户收取的税费。 */
    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    var taxAmount: BigDecimal = BigDecimal.ZERO,

    /** 本订单抵扣的优惠金额。 */
    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    var discountAmount: BigDecimal = BigDecimal.ZERO,

    /** 最终应付总额，包含商品、运费和税费并扣除优惠。 */
    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    var totalAmount: BigDecimal = BigDecimal.ZERO,

    /** 订单结算币种，使用大写 ISO 4217 代码。 */
    @field:Pattern(regexp = "^[A-Z]{3}$")
    @Column(nullable = false, length = 3)
    var currency: String = "USD",

    /** 支付提供商侧的支付意图标识，用于查询、回调和取消支付。 */
    @Column(name = "payment_intent_id", unique = true, length = 255)
    var paymentIntentId: String? = null,

    /** Stripe Checkout 托管支付会话标识，用于回调关联和使未完成会话失效。 */
    @Column(name = "stripe_checkout_session_id", unique = true, length = 255)
    var stripeCheckoutSessionId: String? = null,

    /** 下单时固化的收货地址快照。 */
    @field:Valid
    @Embedded
    var shippingAddress: OrderShippingAddress = OrderShippingAddress(),

    /** 客户在下单时填写的可选备注。 */
    @field:Size(max = 500)
    @Column(name = "client_message", length = 500)
    var clientMessage: String? = null,

    /** 待支付订单的支付截止时间。 */
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    /** 支付成功并完成本地状态推进的时间。 */
    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    /** 订单取消生效的时间。 */
    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    /** 首次发货的时间。 */
    @Column(name = "shipped_at")
    var shippedAt: Instant? = null,

    /** 确认签收或配送完成的时间。 */
    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null,

    /** 取消订单时记录的原因。 */
    @field:Size(max = 200)
    @Column(name = "cancel_reason", length = 200)
    var cancelReason: String? = null,

    /** 订单创建时间，由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    /** 订单最后更新时间，由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
)
