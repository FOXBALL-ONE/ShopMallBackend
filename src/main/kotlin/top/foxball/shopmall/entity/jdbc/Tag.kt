package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * 可复用的商品或内容标签。
 * 标签本身不限定所属业务对象，具体的关联关系由后续的商品或内容模型维护。
 */
@Entity
@Table(
    name = "tags",
    indexes = [
        Index(name = "idx_tags_active_sort_order", columnList = "active, sort_order"),
    ],
)
class Tag(
    /** 标签的数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 标签的显示名称，在整个标签目录中唯一，避免出现语义重复的标签。 */
    @field:NotBlank
    @field:Size(max = 64)
    @Column(nullable = false, unique = true, length = 64)
    var name: String = "",

    /** 可选的标签说明，便于运营人员区分用途相近的标签。 */
    @field:Size(max = 255)
    @Column(length = 255)
    var description: String? = null,

    /** 可选的标签显示色，使用 #RRGGBB 格式。 */
    @field:Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    @Column(length = 7)
    var color: String? = null,

    /** 同一展示区域内的升序排列值，数值越小越靠前。 */
    @field:Min(0)
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    /** 是否允许在前台展示或分配给新的业务对象。 */
    @Column(nullable = false)
    var active: Boolean = true,

    /** 标签首次持久化时由 Hibernate 自动写入。 */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    /** 标签每次持久化更新后由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null,
)
