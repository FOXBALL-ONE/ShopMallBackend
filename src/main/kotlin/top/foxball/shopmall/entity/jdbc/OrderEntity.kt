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
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @field:Size(max = 32)
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    var orderNo: String = "",

    @field:Min(1)
    @Column(name = "customer_id", nullable = false)
    var customerId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: OrderStatus = OrderStatus.PENDING_PAYMENT,

    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "items_subtotal", nullable = false, precision = 12, scale = 2)
    var itemsSubtotal: BigDecimal = BigDecimal.ZERO,

    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "shipping_fee", nullable = false, precision = 12, scale = 2)
    var shippingFee: BigDecimal = BigDecimal.ZERO,

    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    var taxAmount: BigDecimal = BigDecimal.ZERO,

    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    var discountAmount: BigDecimal = BigDecimal.ZERO,

    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    var totalAmount: BigDecimal = BigDecimal.ZERO,

    @field:Pattern(regexp = "^[A-Z]{3}$")
    @Column(nullable = false, length = 3)
    var currency: String = "USD",

    @Column(name = "payment_intent_id", unique = true, length = 64)
    var paymentIntentId: String? = null,

    @field:Valid
    @Embedded
    var shippingAddress: OrderShippingAddress = OrderShippingAddress(),

    @field:Size(max = 500)
    @Column(name = "client_message", length = 500)
    var clientMessage: String? = null,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    @Column(name = "shipped_at")
    var shippedAt: Instant? = null,

    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null,

    @field:Size(max = 200)
    @Column(name = "cancel_reason", length = 200)
    var cancelReason: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
)
