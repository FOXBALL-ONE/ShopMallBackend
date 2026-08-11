package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** 面向客户前台的站内公告。 */
@Entity
@Table(
    name = "announcements",
    indexes = [
        Index(name = "idx_announcements_status_effective", columnList = "status,effective_from,effective_until"),
        Index(name = "idx_announcements_auto_show", columnList = "auto_show_enabled,status,priority"),
        Index(name = "idx_announcements_history", columnList = "public_history,published_at"),
    ],
)
class Announcement(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 用于管理端编辑冲突检测。 */
    @Version
    @Column(nullable = false)
    var version: Long? = null,

    @field:NotBlank
    @field:Size(max = 120)
    @Column(nullable = false, length = 120)
    var title: String = "",

    @field:NotBlank
    @field:Size(max = 255)
    @Column(nullable = false, length = 255)
    var summary: String = "",

    /** 首期按纯文本安全渲染；换行由前台保留。 */
    @field:NotBlank
    @Lob
    @Column(nullable = false)
    var content: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var type: Type = Type.GENERAL,

    /** 数值越大优先级越高。 */
    @field:Min(0)
    @field:Max(100)
    @Column(nullable = false)
    var priority: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.DRAFT,

    /** 已结束或下线后是否可在客户历史公告中查询。 */
    @Column(name = "public_history", nullable = false)
    var publicHistory: Boolean = true,

    /** 是否可在客户网站首屏加载完成后主动展示。 */
    @Column(name = "auto_show_enabled", nullable = false)
    var autoShowEnabled: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_show_mode", nullable = false, length = 32)
    var autoShowMode: AutoShowMode = AutoShowMode.ONCE_PER_ANNOUNCEMENT,

    /** 仅在 [AutoShowMode.COOLDOWN] 下使用，单位为小时。 */
    @field:Min(1)
    @field:Max(24 * 30)
    @Column(name = "auto_show_cooldown_hours")
    var autoShowCooldownHours: Int? = null,

    /** 首期只投放客户 Web；保留字段便于将来增加 App 等渠道。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var channel: Channel = Channel.CUSTOMER_WEB,

    /** 可选行动链接；仅允许站内路径或 HTTPS URL。 */
    @field:Size(max = 512)
    @Column(name = "action_url", length = 512)
    var actionUrl: String? = null,

    @Column(name = "effective_from", nullable = false)
    var effectiveFrom: LocalDateTime = LocalDateTime.now(),

    @Column(name = "effective_until")
    var effectiveUntil: LocalDateTime? = null,

    @Column(name = "published_at")
    var publishedAt: LocalDateTime? = null,

    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: Long = 0,

    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long = 0,

    @Column(name = "archived_at")
    var archivedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,
) {
    enum class Type {
        GENERAL,
        IMPORTANT,
        MAINTENANCE,
        PROMOTION,
    }

    enum class Status {
        DRAFT,
        SCHEDULED,
        PUBLISHED,
        OFFLINE,
        EXPIRED,
        ARCHIVED,
    }

    enum class AutoShowMode {
        ONCE_PER_ANNOUNCEMENT,
        ONCE_PER_BROWSER_SESSION,
        COOLDOWN,
        EVERY_LOAD,
    }

    enum class Channel {
        CUSTOMER_WEB,
    }
}
