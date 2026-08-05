package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
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
import jakarta.persistence.Version
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

/**
 * 客户提交的支持工单。
 *
 * 售前咨询可以不关联订单；售后支持必须关联当前客户名下的订单。工单优先级默认低，
 * 后续由管理员更新处理状态、优先级和回复内容。
 */
@Entity
@Table(
    name = "support_tickets",
    indexes = [
        Index(name = "idx_support_tickets_customer_created", columnList = "customer_id,created_at"),
        Index(name = "idx_support_tickets_status_priority_created", columnList = "status,priority,created_at"),
        Index(name = "idx_support_tickets_order", columnList = "order_id"),
    ],
)
class SupportTicket(
    /** 工单数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 乐观锁版本，避免并发消息、关闭和管理操作互相覆盖。 */
    @Version
    @Column(nullable = false)
    var version: Long? = null,

    /** 提交工单的客户 ID。 */
    @field:Min(1)
    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: Long = 0,

    /** 客户选择的支持服务类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 24)
    var serviceType: SupportServiceType = SupportServiceType.PRE_SALES,

    /** 工单处理优先级；客户不指定时默认为低。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var priority: SupportTicketPriority = SupportTicketPriority.LOW,

    /** 售后支持所关联的订单；售前咨询通常为空。 */
    @get:JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    var order: OrderEntity? = null,

    /** 工单主题。 */
    @field:NotBlank
    @field:Size(max = 120)
    @Column(nullable = false, length = 120)
    var subject: String = "",

    /** 客户描述的具体问题。 */
    @field:NotBlank
    @field:Size(max = 5_000)
    @Column(nullable = false, length = 5_000)
    var content: String = "",

    /** 工单当前处理状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: SupportTicketStatus = SupportTicketStatus.OPEN,

    /** 管理员最后一次公开回复。 */
    @field:Size(max = 5_000)
    @Column(name = "admin_reply", length = 5_000)
    var adminReply: String? = null,

    /** 最后处理该工单的管理员 ID。 */
    @Column(name = "handled_by")
    var handledBy: Long? = null,

    /** 管理员最后一次回复时间。 */
    @Column(name = "replied_at")
    var repliedAt: Instant? = null,

    /** 工单最后一次被标记为已解决的时间。 */
    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,

    /** 工单关闭时间。 */
    @Column(name = "closed_at")
    var closedAt: Instant? = null,

    /** 工单首次持久化时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    /** 工单最后更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
) {
    /** 在实体校验层再次保证售后工单不能脱离订单独立存在。 */
    @get:AssertTrue(message = "售后支持必须选择订单")
    val hasRequiredOrder: Boolean
        get() = serviceType != SupportServiceType.AFTER_SALES || order != null

    /** 任意已关联订单都必须属于提交工单的客户。 */
    @get:AssertTrue(message = "关联订单必须属于当前用户")
    val ownsLinkedOrder: Boolean
        get() = order == null || order?.customerId == customerId
}

/** 客户可选择的支持服务。 */
enum class SupportServiceType {
    PRE_SALES,
    AFTER_SALES,
}

/** 工单处理优先级。 */
enum class SupportTicketPriority {
    LOW,
    MEDIUM,
    HIGH,
}

/** 工单从提交到关闭的处理状态。 */
enum class SupportTicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
}

