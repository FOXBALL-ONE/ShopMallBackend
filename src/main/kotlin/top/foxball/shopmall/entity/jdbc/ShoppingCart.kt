package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

/**
 * 用户购物车。购物车只保存商品引用与期望数量，不锁定商品价格或库存。
 * 展示与下单时必须以 [Product] 的当前数据为准。
 */
@Entity
@Table(
    name = "shopping_carts",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_shopping_carts_customer", columnNames = ["customer_id"]),
    ],
)
class ShoppingCart(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 购物车所属用户；每个用户最多拥有一个购物车。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var customer: User? = null,

    /** 购物车商品明细，删除购物车或移除明细时同步清理子记录。 */
    @get:JsonIgnore
    @field:Valid
    @field:Size(max = 50)
    @OneToMany(
        mappedBy = "cart",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("createdAt ASC, id ASC")
    var items: MutableList<CartItem> = mutableListOf(),

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,

    /** 防止未加锁的并发修改静默覆盖购物车内容。 */
    @Version
    @Column(nullable = false)
    var version: Long = 0,
) {
    fun add(item: CartItem) {
        item.cart = this
        items.add(item)
    }

    fun remove(item: CartItem) {
        items.remove(item)
        item.cart = null
    }

    fun clear() {
        items.forEach { it.cart = null }
        items.clear()
    }
}

/** 购物车中的单个商品及期望购买数量。 */
@Entity
@Table(
    name = "shopping_cart_items",
    indexes = [
        Index(name = "idx_cart_items_product", columnList = "product_id"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_cart_items_cart_product", columnNames = ["cart_id", "product_id"]),
    ],
)
class CartItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    var cart: ShoppingCart? = null,

    @get:JsonIgnore
    @field:NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    var product: Product? = null,

    @field:Min(1)
    @field:Max(99)
    @Column(nullable = false)
    var quantity: Int = 1,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
)
