package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.service.AnnouncementService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/** 管理员公告创建、发布、下线、归档和审计查询接口。 */
@Validated
@RestController
@RequestMapping("/admin/api/announcements")
class AdminAnnouncementController(
    private val announcementService: AnnouncementService,
    private val builder: ResponseBuilder,
) {
    @GetMapping
    fun listAnnouncements(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("page", defaultValue = "0") @Min(0) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) size: Int,
        @RequestParam("keyword", required = false) @Size(max = 120) keyword: String?,
        @RequestParam("status", required = false) status: Announcement.Status?,
        @RequestParam("type", required = false) type: Announcement.Type?,
        @RequestParam("priority_min", required = false) @Min(0) @Max(100) priorityMin: Int?,
        @RequestParam("priority_max", required = false) @Min(0) @Max(100) priorityMax: Int?,
        @RequestParam("auto_show_enabled", required = false) autoShowEnabled: Boolean?,
        @RequestParam("effective_from_start", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveFromStart: LocalDateTime?,
        @RequestParam("effective_from_end", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveFromEnd: LocalDateTime?,
        @RequestParam("sort_by", defaultValue = "UPDATED_AT") sortBy: AnnouncementService.SortBy,
        @RequestParam("sort_direction", defaultValue = "DESC") sortDirection: String,
    ): ResponseEntity<Response> {
        data class AnnouncementData(
            val id: Long,
            val version: Long,
            val title: String,
            val summary: String,
            val type: String,
            val priority: Int,
            val status: String,
            @param:JsonProperty("public_history")
            val publicHistory: Boolean,
            @param:JsonProperty("auto_show_enabled")
            val autoShowEnabled: Boolean,
            @param:JsonProperty("auto_show_mode")
            val autoShowMode: String,
            @param:JsonProperty("auto_show_cooldown_hours")
            val autoShowCooldownHours: Int?,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until")
            val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("published_at")
            val publishedAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
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

        val normalizedSortDirection = sortDirection.uppercase()
        if (normalizedSortDirection !in setOf("ASC", "DESC")) {
            return builder.badRequest().message("sort_direction 只能为 ASC 或 DESC").build()
        }
        val result = announcementService.listForAdmin(
            adminId,
            AnnouncementService.AdminQuery(
                page = page,
                size = size,
                keyword = keyword,
                status = status,
                type = type,
                priorityMin = priorityMin,
                priorityMax = priorityMax,
                autoShowEnabled = autoShowEnabled,
                effectiveFromStart = effectiveFromStart,
                effectiveFromEnd = effectiveFromEnd,
                sortBy = sortBy,
                ascending = normalizedSortDirection == "ASC",
            ),
        )
        val rs = Response(
            items = result.content.map { announcement ->
                AnnouncementData(
                    id = requireNotNull(announcement.id),
                    version = announcement.version ?: 0,
                    title = announcement.title,
                    summary = announcement.summary,
                    type = announcement.type.name,
                    priority = announcement.priority,
                    status = announcement.status.name,
                    publicHistory = announcement.publicHistory,
                    autoShowEnabled = announcement.autoShowEnabled,
                    autoShowMode = announcement.autoShowMode.name,
                    autoShowCooldownHours = announcement.autoShowCooldownHours,
                    effectiveFrom = announcement.effectiveFrom,
                    effectiveUntil = announcement.effectiveUntil,
                    publishedAt = announcement.publishedAt,
                    updatedAt = announcement.updatedAt,
                )
            },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping
    fun createAnnouncement(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("title") @NotBlank @Size(max = 120) title: String,
        @RequestParam("summary") @NotBlank @Size(max = 255) summary: String,
        @RequestParam("content") @NotBlank @Size(max = 20_000) content: String,
        @RequestParam("type") type: Announcement.Type,
        @RequestParam("priority") @Min(0) @Max(100) priority: Int,
        @RequestParam("public_history", defaultValue = "true") publicHistory: Boolean,
        @RequestParam("auto_show_enabled", defaultValue = "false") autoShowEnabled: Boolean,
        @RequestParam("auto_show_mode", defaultValue = "ONCE_PER_ANNOUNCEMENT")
        autoShowMode: Announcement.AutoShowMode,
        @RequestParam("auto_show_cooldown_hours", required = false) @Min(1) @Max(720)
        autoShowCooldownHours: Int?,
        @RequestParam("action_url", required = false) @Size(max = 512) actionUrl: String?,
        @RequestParam("published_at", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        publishedAt: LocalDateTime?,
        @RequestParam("effective_from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveFrom: LocalDateTime,
        @RequestParam("effective_until", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveUntil: LocalDateTime?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val title: String,
            val summary: String,
            val content: String,
            val type: String,
            val priority: Int,
            val status: String,
            @param:JsonProperty("public_history")
            val publicHistory: Boolean,
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
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val announcement = announcementService.create(
            adminId,
            AnnouncementService.CreateCommand(
                title = title,
                summary = summary,
                content = content,
                type = type,
                priority = priority,
                publicHistory = publicHistory,
                autoShowEnabled = autoShowEnabled,
                autoShowMode = autoShowMode,
                autoShowCooldownHours = autoShowCooldownHours,
                actionUrl = actionUrl,
                publishedAt = publishedAt,
                effectiveFrom = effectiveFrom,
                effectiveUntil = effectiveUntil,
            ),
        )
        val rs = Response(
            id = requireNotNull(announcement.id),
            version = announcement.version ?: 0,
            title = announcement.title,
            summary = announcement.summary,
            content = announcement.content,
            type = announcement.type.name,
            priority = announcement.priority,
            status = announcement.status.name,
            publicHistory = announcement.publicHistory,
            autoShowEnabled = announcement.autoShowEnabled,
            autoShowMode = announcement.autoShowMode.name,
            autoShowCooldownHours = announcement.autoShowCooldownHours,
            actionUrl = announcement.actionUrl,
            effectiveFrom = announcement.effectiveFrom,
            effectiveUntil = announcement.effectiveUntil,
            publishedAt = announcement.publishedAt,
            createdAt = announcement.createdAt,
            updatedAt = announcement.updatedAt,
        )
        return builder.created().data(rs).build()
    }

    @GetMapping("/{id}")
    fun announcementDetail(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val title: String,
            val summary: String,
            val content: String,
            val type: String,
            val priority: Int,
            val status: String,
            @param:JsonProperty("public_history")
            val publicHistory: Boolean,
            @param:JsonProperty("auto_show_enabled")
            val autoShowEnabled: Boolean,
            @param:JsonProperty("auto_show_mode")
            val autoShowMode: String,
            @param:JsonProperty("auto_show_cooldown_hours")
            val autoShowCooldownHours: Int?,
            @param:JsonProperty("channel")
            val channel: String,
            @param:JsonProperty("action_url")
            val actionUrl: String?,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until")
            val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("published_at")
            val publishedAt: LocalDateTime?,
            @param:JsonProperty("created_by")
            val createdBy: Long,
            @param:JsonProperty("updated_by")
            val updatedBy: Long,
            @param:JsonProperty("archived_at")
            val archivedAt: LocalDateTime?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val announcement = announcementService.getForAdmin(adminId, id)
            ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(
            id = requireNotNull(announcement.id),
            version = announcement.version ?: 0,
            title = announcement.title,
            summary = announcement.summary,
            content = announcement.content,
            type = announcement.type.name,
            priority = announcement.priority,
            status = announcement.status.name,
            publicHistory = announcement.publicHistory,
            autoShowEnabled = announcement.autoShowEnabled,
            autoShowMode = announcement.autoShowMode.name,
            autoShowCooldownHours = announcement.autoShowCooldownHours,
            channel = announcement.channel.name,
            actionUrl = announcement.actionUrl,
            effectiveFrom = announcement.effectiveFrom,
            effectiveUntil = announcement.effectiveUntil,
            publishedAt = announcement.publishedAt,
            createdBy = announcement.createdBy,
            updatedBy = announcement.updatedBy,
            archivedAt = announcement.archivedAt,
            createdAt = announcement.createdAt,
            updatedAt = announcement.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    @PutMapping("/{id}")
    fun updateAnnouncement(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("title") @NotBlank @Size(max = 120) title: String,
        @RequestParam("summary") @NotBlank @Size(max = 255) summary: String,
        @RequestParam("content") @NotBlank @Size(max = 20_000) content: String,
        @RequestParam("type") type: Announcement.Type,
        @RequestParam("priority") @Min(0) @Max(100) priority: Int,
        @RequestParam("public_history") publicHistory: Boolean,
        @RequestParam("auto_show_enabled") autoShowEnabled: Boolean,
        @RequestParam("auto_show_mode") autoShowMode: Announcement.AutoShowMode,
        @RequestParam("auto_show_cooldown_hours", required = false) @Min(1) @Max(720)
        autoShowCooldownHours: Int?,
        @RequestParam("action_url", required = false) @Size(max = 512) actionUrl: String?,
        @RequestParam("published_at", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        publishedAt: LocalDateTime?,
        @RequestParam("effective_from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveFrom: LocalDateTime,
        @RequestParam("effective_until", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveUntil: LocalDateTime?,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val title: String,
            val summary: String,
            val content: String,
            val type: String,
            val priority: Int,
            val status: String,
            @param:JsonProperty("public_history")
            val publicHistory: Boolean,
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
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val announcement = announcementService.update(
            adminId,
            id,
            AnnouncementService.UpdateCommand(
                title = title,
                summary = summary,
                content = content,
                type = type,
                priority = priority,
                publicHistory = publicHistory,
                autoShowEnabled = autoShowEnabled,
                autoShowMode = autoShowMode,
                autoShowCooldownHours = autoShowCooldownHours,
                actionUrl = actionUrl,
                publishedAt = publishedAt,
                effectiveFrom = effectiveFrom,
                effectiveUntil = effectiveUntil,
                expectedVersion = expectedVersion,
            ),
        ) ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(
            id = requireNotNull(announcement.id),
            version = announcement.version ?: 0,
            title = announcement.title,
            summary = announcement.summary,
            content = announcement.content,
            type = announcement.type.name,
            priority = announcement.priority,
            status = announcement.status.name,
            publicHistory = announcement.publicHistory,
            autoShowEnabled = announcement.autoShowEnabled,
            autoShowMode = announcement.autoShowMode.name,
            autoShowCooldownHours = announcement.autoShowCooldownHours,
            actionUrl = announcement.actionUrl,
            effectiveFrom = announcement.effectiveFrom,
            effectiveUntil = announcement.effectiveUntil,
            publishedAt = announcement.publishedAt,
            updatedAt = announcement.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/{id}/publish")
    fun publishAnnouncement(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val status: String,
            @param:JsonProperty("published_at")
            val publishedAt: LocalDateTime?,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until")
            val effectiveUntil: LocalDateTime?,
        )

        val announcement = announcementService.publish(adminId, id, expectedVersion)
            ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(
            id = requireNotNull(announcement.id),
            version = announcement.version ?: 0,
            status = announcement.status.name,
            publishedAt = announcement.publishedAt,
            effectiveFrom = announcement.effectiveFrom,
            effectiveUntil = announcement.effectiveUntil,
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/{id}/offline")
    fun offlineAnnouncement(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
        @RequestParam("reason") @NotBlank @Size(max = 255) reason: String,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val status: String,
            @param:JsonProperty("public_history")
            val publicHistory: Boolean,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val announcement = announcementService.offline(adminId, id, expectedVersion, reason)
            ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(
            id = requireNotNull(announcement.id),
            version = announcement.version ?: 0,
            status = announcement.status.name,
            publicHistory = announcement.publicHistory,
            updatedAt = announcement.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/{id}/archive")
    fun archiveAnnouncement(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
        @RequestParam("public_history", defaultValue = "true") publicHistory: Boolean,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val status: String,
            @param:JsonProperty("public_history")
            val publicHistory: Boolean,
            @param:JsonProperty("archived_at")
            val archivedAt: LocalDateTime?,
        )

        val announcement = announcementService.archive(adminId, id, expectedVersion, publicHistory)
            ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(
            id = requireNotNull(announcement.id),
            version = announcement.version ?: 0,
            status = announcement.status.name,
            publicHistory = announcement.publicHistory,
            archivedAt = announcement.archivedAt,
        )
        return builder.ok().data(rs).build()
    }

    @DeleteMapping("/{id}")
    fun deleteAnnouncement(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val status: String,
        )

        val announcement = announcementService.delete(adminId, id)
            ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(requireNotNull(announcement.id), "DELETED")
        return builder.ok().data(rs).build()
    }

    @DeleteMapping("/batch")
    fun deleteAnnouncements(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: Set<Long>,
    ): ResponseEntity<Response> {
        data class Response(
            val ids: List<Long>,
            val deleted: Int,
        )

        val deletedIds = announcementService.deleteBatch(adminId, ids)
            ?: return builder.notFound().message("部分公告不存在").build()
        val rs = Response(deletedIds, deletedIds.size)
        return builder.ok().data(rs).build()
    }

    @PostMapping("/{id}/copy")
    fun copyAnnouncement(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val title: String,
            val status: String,
            @param:JsonProperty("effective_from")
            val effectiveFrom: LocalDateTime,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
        )

        val announcement = announcementService.copy(adminId, id, expectedVersion)
            ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(
            id = requireNotNull(announcement.id),
            version = announcement.version ?: 0,
            title = announcement.title,
            status = announcement.status.name,
            effectiveFrom = announcement.effectiveFrom,
            createdAt = announcement.createdAt,
        )
        return builder.created().data(rs).build()
    }

    @GetMapping("/{id}/audit-logs")
    fun auditLogs(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("page", defaultValue = "0") @Min(0) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<Response> {
        data class AuditLogData(
            val id: Long,
            @param:JsonProperty("announcement_id")
            val announcementId: Long,
            @param:JsonProperty("operator_id")
            val operatorId: Long,
            val action: String,
            @param:JsonProperty("before_snapshot")
            val beforeSnapshot: String?,
            @param:JsonProperty("after_snapshot")
            val afterSnapshot: String,
            val reason: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
        )
        data class Response(
            val items: List<AuditLogData>,
            val page: Int,
            val size: Int,
            @param:JsonProperty("total_elements")
            val totalElements: Long,
            @param:JsonProperty("total_pages")
            val totalPages: Int,
        )

        val result = announcementService.auditLogs(adminId, id, page, size)
            ?: return builder.notFound().message("公告不存在").build()
        val rs = Response(
            items = result.content.map { log ->
                AuditLogData(
                    id = requireNotNull(log.id),
                    announcementId = log.announcementId,
                    operatorId = log.operatorId,
                    action = log.action.name,
                    beforeSnapshot = log.beforeSnapshot,
                    afterSnapshot = log.afterSnapshot,
                    reason = log.reason,
                    createdAt = log.createdAt,
                )
            },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
        return builder.ok().data(rs).build()
    }
}
