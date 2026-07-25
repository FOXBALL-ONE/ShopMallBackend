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
import java.time.Instant

@Entity
@Table(name = "order_items")
class OrderItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    var order: OrderEntity? = null,

    @field:Min(1)
    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long = 0,

    @field:Size(max = 2000)
    @Column(name = "product_snapshot", nullable = false, length = 2000, updatable = false)
    var productSnapshot: String = "",

    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    var unitPrice: BigDecimal = BigDecimal.ZERO,

    @field:Min(1)
    @Column(nullable = false, updatable = false)
    var quantity: Int = 1,

    @field:Digits(integer = 10, fraction = 2)
    @Column(name = "line_total", nullable = false, precision = 12, scale = 2, updatable = false)
    var lineTotal: BigDecimal = BigDecimal.ZERO,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
