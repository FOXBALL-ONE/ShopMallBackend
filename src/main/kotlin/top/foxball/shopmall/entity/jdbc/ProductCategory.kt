package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import jakarta.validation.constraints.Size as ValidationSize

/** 商品分类；支持通过 [parent] 构建多级分类树。 */
@Entity
@DynamicUpdate
@Table(
    name = "product_categories",
    indexes = [
        Index(name = "idx_product_categories_parent_id", columnList = "parent_id"),
        Index(name = "idx_product_categories_status", columnList = "status"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_product_categories_code", columnNames = ["code"]),
    ],
)
class ProductCategory(
    /** 分类数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 稳定分类代码，用于接口、路由和内部引用。 */
    @field:NotBlank
    @field:ValidationSize(max = 64)
    @field:Pattern(regexp = "^[a-z][a-z0-9-]*$")
    @Column(nullable = false, updatable = false, length = 64)
    var code: String = "",

    /** 面向用户展示的分类名称。 */
    @field:NotBlank
    @field:ValidationSize(max = 100)
    @Column(nullable = false, length = 100)
    var name: String = "",

    /** 分类说明。 */
    @field:ValidationSize(max = 1_000)
    @Column(length = 1_000)
    var description: String? = null,

    /** 上级分类；为空表示顶级分类。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: ProductCategory? = null,

    /** 同级分类的展示顺序。 */
    @field:Min(0)
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    /** 分类是否可在前台使用。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.ACTIVE,

    /** 分类首次持久化时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    /** 分类最近更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    /** 分类并发编辑版本。 */
    @Version
    @Column(nullable = false)
    var version: Long = 0,
) {
    @get:Transient
    @get:AssertTrue(message = "分类不能将自身设置为上级分类")
    val parentIsNotSelf: Boolean
        get() = id == null || parent?.id != id

    enum class Status {
        ACTIVE,
        INACTIVE,
    }
}
