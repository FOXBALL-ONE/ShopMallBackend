package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState
import top.foxball.shopmall.service.AnnouncementService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/** 面向客户网站的公开公告、历史公告和已读状态接口。 */
@Validated
@RestController
class AnnouncementController(
    private val announcementService: AnnouncementService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/announcements/current")
    fun currentAnnouncements(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("include_read", defaultValue = "true") includeRead: Boolean,
        @RequestParam("limit", defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ): ResponseEntity<Response> {
        data class AnnouncementData(
            val id: Long,
            val title: String,
            val summary: String,
            val type: String,
            val priority: Int,
            @param:JsonProperty("auto_show_enabled")
            val autoShowEnabled: Boolean,
            @param:JsonProperty("auto_show_mode")
            val autoShowMode: String,
            @param:JsonProperty("auto_show_cooldown_hours")
            val autoShowCooldownHours: Int?,
            @param:JsonProperty("action_url")
            val actionUrl: String?,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until")
            val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("published_at")
            val publishedAt: LocalDateTime?,
            @param:JsonProperty("is_read")
            val isRead: Boolean,
            @param:JsonProperty("user_state")
            val userState: String?,
        )
        data class Response(
            val items: List<AnnouncementData>,
        )

        val items = announcementService.currentForCustomer(userId, includeRead, limit)
            .asSequence()
            .map { item ->
                val announcement = item.announcement
                AnnouncementData(
                    id = requireNotNull(announcement.id),
                    title = announcement.title,
                    summary = announcement.summary,
                    type = announcement.type.name,
                    priority = announcement.priority,
                    autoShowEnabled = announcement.autoShowEnabled,
                    autoShowMode = announcement.autoShowMode.name,
                    autoShowCooldownHours = announcement.autoShowCooldownHours,
                    actionUrl = announcement.actionUrl,
                    effectiveFrom = announcement.effectiveFrom,
                    effectiveUntil = announcement.effectiveUntil,
                    publishedAt = announcement.publishedAt,
                    isRead = item.isRead,
                    userState = item.userState?.state?.name,
                )
            }
            .toList()
        val rs = Response(items = items)
        return builder.ok().data(rs).build()
    }

    @GetMapping("/api/announcements/auto-show")
    fun autoShowAnnouncement(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("excluded_ids", required = false) excludedIds: List<Long>?,
    ): ResponseEntity<Response> {
        data class AnnouncementData(
            val id: Long,
            val title: String,
            val summary: String,
            val content: String,
            val type: String,
            val priority: Int,
            @param:JsonProperty("auto_show_mode")
            val autoShowMode: String,
            @param:JsonProperty("auto_show_cooldown_hours")
            val autoShowCooldownHours: Int?,
            @param:JsonProperty("action_url")
            val actionUrl: String?,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until")
            val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("published_at")
            val publishedAt: LocalDateTime?,
            @param:JsonProperty("is_read")
            val isRead: Boolean,
            @param:JsonProperty("user_state")
            val userState: String?,
        )
        data class Response(
            val announcement: AnnouncementData?,
        )

        val item = announcementService.autoShowForCustomer(userId, excludedIds.orEmpty())
        val rs = Response(
            announcement = item?.let {
                val announcement = it.announcement
                AnnouncementData(
                    id = requireNotNull(announcement.id),
                    title = announcement.title,
                    summary = announcement.summary,
                    content = announcement.content,
                    type = announcement.type.name,
                    priority = announcement.priority,
                    autoShowMode = announcement.autoShowMode.name,
                    autoShowCooldownHours = announcement.autoShowCooldownHours,
                    actionUrl = announcement.actionUrl,
                    effectiveFrom = announcement.effectiveFrom,
                    effectiveUntil = announcement.effectiveUntil,
                    publishedAt = announcement.publishedAt,
                    isRead = it.isRead,
                    userState = it.userState?.state?.name,
                )
            },
        )
        return builder.ok().data(rs).build()
    }

    @GetMapping("/api/announcements/history")
    fun historyAnnouncements(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("page", defaultValue = "0") @Min(0) page: Int,
        @RequestParam("size", defaultValue = "20") @Min(1) @Max(50) size: Int,
        @RequestParam("type", required = false) type: Announcement.Type?,
        @RequestParam("year", required = false) @Min(2000) @Max(9999) year: Int?,
        @RequestParam("keyword", required = false) @Size(max = 120) keyword: String?,
    ): ResponseEntity<Response> {
        data class AnnouncementData(
            val id: Long,
            val title: String,
            val summary: String,
            val type: String,
            val priority: Int,
            @param:JsonProperty("action_url")
            val actionUrl: String?,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until")
            val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("published_at")
            val publishedAt: LocalDateTime?,
            @param:JsonProperty("is_read")
            val isRead: Boolean,
        )
        data class Response(
            val items: List<AnnouncementData>,
            val page: Int,
            val size: Int,
            @param:JsonProperty("total_elements")
            val totalElements: Long,
            @param:JsonProperty("total_pages")
            val totalPages: Int,
        )

        val result = announcementService.historyForCustomer(
            userId,
            AnnouncementService.HistoryQuery(
                page = page,
                size = size,
                type = type,
                year = year,
                keyword = keyword,
            ),
        )
        val rs = Response(
            items = result.content.map { item ->
                val announcement = item.announcement
                AnnouncementData(
                    id = requireNotNull(announcement.id),
                    title = announcement.title,
                    summary = announcement.summary,
                    type = announcement.type.name,
                    priority = announcement.priority,
                    actionUrl = announcement.actionUrl,
                    effectiveFrom = announcement.effectiveFrom,
                    effectiveUntil = announcement.effectiveUntil,
                    publishedAt = announcement.publishedAt,
                    isRead = item.isRead,
                )
            },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
        return builder.ok().data(rs).build()
    }

    @GetMapping("/api/announcements/{id}")
    fun announcementDetail(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val title: String,
            val summary: String,
            val content: String,
            val type: String,
            val priority: Int,
            @param:JsonProperty("action_url")
            val actionUrl: String?,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until")
            val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("published_at")
            val publishedAt: LocalDateTime?,
            @param:JsonProperty("is_read")
            val isRead: Boolean,
            @param:JsonProperty("user_state")
            val userState: String?,
        )

        val item = announcementService.getForCustomer(userId, id)
            ?: return builder.notFound().message("公告不存在或暂不可访问").build()
        val announcement = item.announcement
        val rs = Response(
            id = requireNotNull(announcement.id),
            title = announcement.title,
            summary = announcement.summary,
            content = announcement.content,
            type = announcement.type.name,
            priority = announcement.priority,
            actionUrl = announcement.actionUrl,
            effectiveFrom = announcement.effectiveFrom,
            effectiveUntil = announcement.effectiveUntil,
            publishedAt = announcement.publishedAt,
            isRead = item.isRead,
            userState = item.userState?.state?.name,
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/api/announcements/{id}/state")
    fun recordAnnouncementState(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("state") state: AnnouncementUserState.State,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val state: String,
            @param:JsonProperty("first_seen_at")
            val firstSeenAt: LocalDateTime,
            @param:JsonProperty("last_seen_at")
            val lastSeenAt: LocalDateTime,
            @param:JsonProperty("dismissed_at")
            val dismissedAt: LocalDateTime?,
            @param:JsonProperty("acknowledged_at")
            val acknowledgedAt: LocalDateTime?,
        )

        val userState = announcementService.recordCustomerState(userId, id, state)
            ?: return builder.notFound().message("公告不存在或暂不可访问").build()
        val rs = Response(
            id = requireNotNull(userState.id),
            state = userState.state.name,
            firstSeenAt = userState.firstSeenAt,
            lastSeenAt = userState.lastSeenAt,
            dismissedAt = userState.dismissedAt,
            acknowledgedAt = userState.acknowledgedAt,
        )
        return builder.ok().data(rs).build()
    }
}
