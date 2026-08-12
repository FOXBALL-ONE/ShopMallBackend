package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 订单中的商品行快照。
 *
 * 商品标识、商品快照、成交单价、数量和行金额在下单后均不可修改，以保证后续商品资料或价格变化
 * 不会影响历史订单；订单关联仅用于持久化和查询，不参与 JSON 序列化。
 */
@Entity
@Table(name = "order_items")
class OrderItem(
    /** 订单明细的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 所属订单，仅用于持久化关联，序列化时忽略。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    var order: OrderEntity? = null,

    /** 下单时所购买商品款式的标识。 */
    @field:Min(1)
    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long = 0,

    /** 下单时所购买 SKU 的标识。 */
    @field:Min(1)
    @Column(name = "variant_id", nullable = false, updatable = false)
    var variantId: Long = 0,

    /** 下单时所购买的稳定 SKU。 */
    @field:Size(max = 64)
    @Column(nullable = false, updatable = false, length = 64)
    var sku: String = "",

    /** 下单时保存的商品展示信息快照。 */
    @field:Size(max = 2000)
    @Column(name = "product_snapshot", nullable = false, length = 2000, updatable = false)
    var productSnapshot: String = "",

    /** 下单时确定的商品单价。 */
    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    var unitPrice: BigDecimal = BigDecimal.ZERO,

    /** 本订单明细购买的商品数量。 */
    @field:Min(1)
    @Column(nullable = false, updatable = false)
    var quantity: Int = 1,

    /** 单价乘以数量得到的明细成交金额。 */
    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "line_total", nullable = false, precision = 12, scale = 2, updatable = false)
    var lineTotal: BigDecimal = BigDecimal.ZERO,

    /** 订单明细创建时间，由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)
