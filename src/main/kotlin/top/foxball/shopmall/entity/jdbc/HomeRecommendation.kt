package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.CascadeType
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
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** 可原子发布的一套客户首页商品推荐配置。 */
@Entity
@Table(
    name = "home_recommendation_plans",
    indexes = [
        Index(name = "idx_home_rec_plan_status_effective", columnList = "channel,status,effective_from,effective_until"),
        Index(name = "idx_home_rec_plan_status_updated", columnList = "status,updated_at"),
    ],
)
class HomeRecommendationPlan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @field:NotBlank
    @field:Size(max = 120)
    @Column(nullable = false, length = 120)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.DRAFT,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var channel: Channel = Channel.CUSTOMER_WEB,

    @Column(name = "effective_from", nullable = false)
    var effectiveFrom: LocalDateTime = LocalDateTime.now(),

    @Column(name = "effective_until")
    var effectiveUntil: LocalDateTime? = null,

    @Column(name = "fallback_enabled", nullable = false)
    var fallbackEnabled: Boolean = true,

    @Column(name = "deduplicate_across_sections", nullable = false)
    var deduplicateAcrossSections: Boolean = true,

    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: Long = 0,

    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long = 0,

    @Column(name = "published_at")
    var publishedAt: LocalDateTime? = null,

    @Column(name = "archived_at")
    var archivedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,
) {
    @OneToMany(mappedBy = "plan", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    @BatchSize(size = 50)
    var sections: MutableList<@Valid HomeRecommendationSection> = mutableListOf()

    fun replaceSections(values: Collection<HomeRecommendationSection>) {
        sections.clear()
        values.forEach { section ->
            section.plan = this
            sections += section
        }
    }

    enum class Status {
        DRAFT,
        SCHEDULED,
        PUBLISHED,
        OFFLINE,
        EXPIRED,
        ARCHIVED,
    }

    enum class Channel {
        CUSTOMER_WEB,
    }
}

/** 首页推荐方案中的一个可视楼层。 */
@Entity
@Table(
    name = "home_recommendation_sections",
    indexes = [Index(name = "idx_home_rec_section_plan_order", columnList = "plan_id,sort_order")],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_home_rec_section_plan_code", columnNames = ["plan_id", "code"]),
    ],
)
class HomeRecommendationSection(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    var plan: HomeRecommendationPlan? = null,

    @field:NotBlank
    @field:Size(max = 64)
    @field:Pattern(regexp = "^[a-z][a-z0-9_]*$")
    @Column(nullable = false, length = 64)
    var code: String = "",

    @field:Size(max = 80)
    @Column(length = 80)
    var eyebrow: String? = null,

    @field:NotBlank
    @field:Size(max = 120)
    @Column(nullable = false, length = 120)
    var title: String = "",

    @field:Size(max = 255)
    @Column(length = 255)
    var subtitle: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "display_style", nullable = false, length = 16)
    var displayStyle: DisplayStyle = DisplayStyle.GRID,

    @field:Min(2)
    @field:Max(6)
    @Column(name = "desktop_columns", nullable = false)
    var desktopColumns: Int = 4,

    @field:Min(1)
    @field:Max(2)
    @Column(name = "mobile_columns", nullable = false)
    var mobileColumns: Int = 2,

    @field:Size(max = 40)
    @Column(name = "link_label", length = 40)
    var linkLabel: String? = null,

    @field:Size(max = 512)
    @Column(name = "link_url", length = 512)
    var linkUrl: String? = null,

    @field:Min(1)
    @field:Max(24)
    @Column(name = "item_limit", nullable = false)
    var itemLimit: Int = 8,

    @Column(name = "hide_when_empty", nullable = false)
    var hideWhenEmpty: Boolean = true,

    @field:Min(0)
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) {
    @OneToMany(mappedBy = "section", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    @BatchSize(size = 100)
    var groups: MutableList<@Valid HomeRecommendationGroup> = mutableListOf()

    fun replaceGroups(values: Collection<HomeRecommendationGroup>) {
        groups.clear()
        values.forEach { group ->
            group.section = this
            groups += group
        }
    }

    enum class DisplayStyle {
        GRID,
        CAROUSEL,
        TABS,
    }
}

/** 推荐楼层中的单组商品，TABS 模式下一个组对应一个页签。 */
@Entity
@Table(
    name = "home_recommendation_groups",
    indexes = [Index(name = "idx_home_rec_group_section_order", columnList = "section_id,sort_order")],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_home_rec_group_section_code", columnNames = ["section_id", "code"]),
    ],
)
class HomeRecommendationGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    var section: HomeRecommendationSection? = null,

    @field:NotBlank
    @field:Size(max = 64)
    @field:Pattern(regexp = "^[a-z][a-z0-9_]*$")
    @Column(nullable = false, length = 64)
    var code: String = "",

    @field:Size(max = 80)
    @Column(length = 80)
    var title: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_mode", nullable = false, length = 16)
    var selectionMode: SelectionMode = SelectionMode.AUTO,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var strategy: Strategy = Strategy.NEW_ARRIVALS,

    @field:Min(1)
    @field:Max(24)
    @Column(name = "item_limit", nullable = false)
    var itemLimit: Int = 8,

    @Column(name = "category_id")
    var categoryId: Long? = null,

    @field:Size(max = 64)
    @Column(name = "product_type", length = 64)
    var productType: String? = null,

    @Column(name = "tag_id")
    var tagId: Long? = null,

    @field:Min(1)
    @field:Max(365)
    @Column(name = "lookback_days")
    var lookbackDays: Int? = 30,

    @field:Min(1)
    @Column(name = "minimum_stock", nullable = false)
    var minimumStock: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_strategy", nullable = false, length = 24)
    var fallbackStrategy: FallbackStrategy = FallbackStrategy.LATEST,

    @field:Min(0)
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) {
    @OneToMany(mappedBy = "group", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    @BatchSize(size = 100)
    var items: MutableList<@Valid HomeRecommendationItem> = mutableListOf()

    fun replaceItems(values: Collection<HomeRecommendationItem>) {
        items.clear()
        values.forEach { item ->
            item.group = this
            items += item
        }
    }

    enum class SelectionMode {
        MANUAL,
        AUTO,
        HYBRID,
    }

    enum class Strategy {
        NEW_ARRIVALS,
        BEST_SELLERS,
        HIGH_RATED,
        EDITOR_PICKS,
    }

    enum class FallbackStrategy {
        NONE,
        LATEST,
        BEST_SELLERS,
    }
}

/** MANUAL/HYBRID 商品组中的人工选品记录，只保存 SPU ID 以允许保留已删除商品的历史配置。 */
@Entity
@Table(
    name = "home_recommendation_items",
    indexes = [Index(name = "idx_home_rec_item_group_order", columnList = "group_id,pinned,sort_order")],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_home_rec_item_group_product", columnNames = ["group_id", "product_id"]),
    ],
)
class HomeRecommendationItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    var group: HomeRecommendationGroup? = null,

    @Column(name = "product_id", nullable = false)
    var productId: Long = 0,

    @Column(nullable = false)
    var pinned: Boolean = false,

    @field:Size(max = 30)
    @Column(name = "custom_badge", length = 30)
    var customBadge: String? = null,

    @field:Min(0)
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)
