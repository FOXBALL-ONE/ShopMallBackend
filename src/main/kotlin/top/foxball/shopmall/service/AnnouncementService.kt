package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.entity.jdbc.AnnouncementAuditLog
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState
import java.time.LocalDateTime

/** 公告管理、客户端公告查询及已读状态记录。 */
interface AnnouncementService {
    enum class SortBy(val property: String) {
        PRIORITY("priority"),
        EFFECTIVE_FROM("effectiveFrom"),
        PUBLISHED_AT("publishedAt"),
        CREATED_AT("createdAt"),
        UPDATED_AT("updatedAt"),
    }

    data class AdminQuery(
        val page: Int = 0,
        val size: Int = 25,
        val keyword: String? = null,
        val status: Announcement.Status? = null,
        val type: Announcement.Type? = null,
        val priorityMin: Int? = null,
        val priorityMax: Int? = null,
        val autoShowEnabled: Boolean? = null,
        val effectiveFromStart: LocalDateTime? = null,
        val effectiveFromEnd: LocalDateTime? = null,
        val sortBy: SortBy = SortBy.UPDATED_AT,
        val ascending: Boolean = false,
    )

    data class HistoryQuery(
        val page: Int = 0,
        val size: Int = 20,
        val type: Announcement.Type? = null,
        val year: Int? = null,
        val keyword: String? = null,
    )

    data class CreateCommand(
        val title: String,
        val summary: String,
        val content: String,
        val type: Announcement.Type,
        val priority: Int,
        val publicHistory: Boolean,
        val autoShowEnabled: Boolean,
        val autoShowMode: Announcement.AutoShowMode,
        val autoShowCooldownHours: Int?,
        val actionUrl: String?,
        val publishedAt: LocalDateTime?,
        val effectiveFrom: LocalDateTime,
        val effectiveUntil: LocalDateTime?,
    )

    data class UpdateCommand(
        val title: String,
        val summary: String,
        val content: String,
        val type: Announcement.Type,
        val priority: Int,
        val publicHistory: Boolean,
        val autoShowEnabled: Boolean,
        val autoShowMode: Announcement.AutoShowMode,
        val autoShowCooldownHours: Int?,
        val actionUrl: String?,
        val publishedAt: LocalDateTime?,
        val effectiveFrom: LocalDateTime,
        val effectiveUntil: LocalDateTime?,
        val expectedVersion: Long,
    )

    data class AudienceItem(
        val announcement: Announcement,
        val userState: AnnouncementUserState?,
    ) {
        val isRead: Boolean
            get() = userState != null
    }

    fun listForAdmin(adminId: Long, query: AdminQuery): Page<Announcement>

    fun getForAdmin(adminId: Long, announcementId: Long): Announcement?

    fun create(adminId: Long, command: CreateCommand): Announcement

    fun update(adminId: Long, announcementId: Long, command: UpdateCommand): Announcement?

    fun publish(adminId: Long, announcementId: Long, expectedVersion: Long): Announcement?

    fun offline(adminId: Long, announcementId: Long, expectedVersion: Long, reason: String): Announcement?

    fun archive(
        adminId: Long,
        announcementId: Long,
        expectedVersion: Long,
        publicHistory: Boolean,
    ): Announcement?

    fun copy(adminId: Long, announcementId: Long, expectedVersion: Long): Announcement?

    fun auditLogs(adminId: Long, announcementId: Long, page: Int, size: Int): Page<AnnouncementAuditLog>?

    fun currentForCustomer(userId: Long?, includeRead: Boolean, limit: Int): List<AudienceItem>

    fun autoShowForCustomer(userId: Long?, excludedIds: Collection<Long>): AudienceItem?

    fun getForCustomer(userId: Long?, announcementId: Long): AudienceItem?

    fun historyForCustomer(userId: Long?, query: HistoryQuery): Page<AudienceItem>

    fun recordCustomerState(
        userId: Long,
        announcementId: Long,
        state: AnnouncementUserState.State,
    ): AnnouncementUserState?

    /** 推进已到生效/失效时间的公告状态；由定时任务和查询入口共同调用。 */
    fun synchronizeLifecycle(): Int
}
