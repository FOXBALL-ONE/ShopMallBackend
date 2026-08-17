package top.foxball.shopmall.service.impl

import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.entity.jdbc.AnnouncementAuditLog
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState
import top.foxball.shopmall.handler.AnnouncementVersionConflictException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.AnnouncementAuditLogRepository
import top.foxball.shopmall.repository.AnnouncementRepository
import top.foxball.shopmall.repository.AnnouncementUserStateRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AnnouncementService
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class AnnouncementServiceImpl(
    private val announcementRepository: AnnouncementRepository,
    private val announcementUserStateRepository: AnnouncementUserStateRepository,
    private val announcementAuditLogRepository: AnnouncementAuditLogRepository,
    private val userRepository: UserRepository,
    private val adminAccessService: AdminAccessService,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    @Value("\${shopmall.announcement.time-zone:Asia/Shanghai}") announcementTimeZone: String,
) : AnnouncementService {
    private val announcementZoneId = ZoneId.of(announcementTimeZone)
    @Transactional
    override fun listForAdmin(adminId: Long, query: AnnouncementService.AdminQuery): Page<Announcement> {
        adminAccessService.requireAdmin(adminId)
        synchronizeLifecycle()
        if (query.priorityMin != null && query.priorityMax != null && query.priorityMin > query.priorityMax) {
            throw ParamErrorException("最低优先级不能大于最高优先级")
        }
        if (
            query.effectiveFromStart != null && query.effectiveFromEnd != null &&
            query.effectiveFromStart > query.effectiveFromEnd
        ) {
            throw ParamErrorException("生效时间起点不能晚于终点")
        }
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
        val specification = Specification<Announcement> { root, _, criteriaBuilder ->
            val predicates = mutableListOf<Predicate>()
            keyword?.let { value ->
                val escaped = value.lowercase()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                val pattern = "%$escaped%"
                predicates += criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern, '\\'),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("summary")), pattern, '\\'),
                )
            }
            query.status?.let { predicates += criteriaBuilder.equal(root.get<Announcement.Status>("status"), it) }
            query.type?.let { predicates += criteriaBuilder.equal(root.get<Announcement.Type>("type"), it) }
            query.priorityMin?.let { predicates += criteriaBuilder.greaterThanOrEqualTo(root.get("priority"), it) }
            query.priorityMax?.let { predicates += criteriaBuilder.lessThanOrEqualTo(root.get("priority"), it) }
            query.autoShowEnabled?.let { predicates += criteriaBuilder.equal(root.get<Boolean>("autoShowEnabled"), it) }
            query.effectiveFromStart?.let {
                predicates += criteriaBuilder.greaterThanOrEqualTo(root.get("effectiveFrom"), it)
            }
            query.effectiveFromEnd?.let {
                predicates += criteriaBuilder.lessThanOrEqualTo(root.get("effectiveFrom"), it)
            }
            criteriaBuilder.and(*predicates.toTypedArray())
        }
        val direction = if (query.ascending) Sort.Direction.ASC else Sort.Direction.DESC
        var sort = Sort.by(direction, query.sortBy.property)
        if (query.sortBy.property != "id") sort = sort.and(Sort.by(Sort.Direction.DESC, "id"))
        return announcementRepository.findAll(
            specification,
            PageRequest.of(query.page.coerceAtLeast(0), query.size.coerceIn(1, 100), sort),
        )
    }

    @Transactional
    override fun getForAdmin(adminId: Long, announcementId: Long): Announcement? {
        adminAccessService.requireAdmin(adminId)
        synchronizeLifecycle()
        return announcementRepository.findById(announcementId).orElse(null)
    }

    @Transactional
    override fun create(adminId: Long, command: AnnouncementService.CreateCommand): Announcement {
        adminAccessService.requireAdmin(adminId)
        val now = now()
        validateCommand(command)
        val announcement = Announcement(
            title = command.title.trim(),
            summary = command.summary.trim(),
            content = command.content.trim(),
            type = command.type,
            priority = command.priority,
            status = Announcement.Status.DRAFT,
            publicHistory = command.publicHistory,
            autoShowEnabled = command.autoShowEnabled,
            autoShowMode = command.autoShowMode,
            autoShowCooldownHours = normalizedCooldown(command.autoShowMode, command.autoShowCooldownHours),
            actionUrl = normalizedActionUrl(command.actionUrl),
            publishedAt = command.publishedAt,
            effectiveFrom = command.effectiveFrom,
            effectiveUntil = command.effectiveUntil,
            createdBy = adminId,
            updatedBy = adminId,
        )
        val saved = announcementRepository.saveAndFlush(announcement)
        appendAudit(saved, adminId, AnnouncementAuditLog.Action.CREATED, null, snapshot(saved), null)
        return saved
    }

    @Transactional
    override fun update(
        adminId: Long,
        announcementId: Long,
        command: AnnouncementService.UpdateCommand,
    ): Announcement? {
        adminAccessService.requireAdmin(adminId)
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return null
        requireExpectedVersion(announcement, command.expectedVersion)
        validateCommand(
            AnnouncementService.CreateCommand(
                title = command.title,
                summary = command.summary,
                content = command.content,
                type = command.type,
                priority = command.priority,
                publicHistory = command.publicHistory,
                autoShowEnabled = command.autoShowEnabled,
                autoShowMode = command.autoShowMode,
                autoShowCooldownHours = command.autoShowCooldownHours,
                actionUrl = command.actionUrl,
                publishedAt = command.publishedAt,
                effectiveFrom = command.effectiveFrom,
                effectiveUntil = command.effectiveUntil,
            ),
        )
        val before = snapshot(announcement)
        announcement.title = command.title.trim()
        announcement.summary = command.summary.trim()
        announcement.content = command.content.trim()
        announcement.type = command.type
        announcement.priority = command.priority
        announcement.publicHistory = command.publicHistory
        announcement.autoShowEnabled = command.autoShowEnabled
        announcement.autoShowMode = command.autoShowMode
        announcement.autoShowCooldownHours = normalizedCooldown(command.autoShowMode, command.autoShowCooldownHours)
        announcement.actionUrl = normalizedActionUrl(command.actionUrl)
        announcement.publishedAt = command.publishedAt
        announcement.effectiveFrom = command.effectiveFrom
        announcement.effectiveUntil = command.effectiveUntil
        announcement.updatedBy = adminId
        if (announcement.status in setOf(
                Announcement.Status.SCHEDULED,
                Announcement.Status.PUBLISHED,
                Announcement.Status.EXPIRED,
            )
        ) {
            announcement.status = lifecycleStatus(announcement.effectiveFrom, announcement.effectiveUntil, now())
        }
        val saved = announcementRepository.saveAndFlush(announcement)
        appendAudit(saved, adminId, AnnouncementAuditLog.Action.UPDATED, before, snapshot(saved), null)
        return saved
    }

    @Transactional
    override fun publish(adminId: Long, announcementId: Long, expectedVersion: Long): Announcement? {
        adminAccessService.requireAdmin(adminId)
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return null
        requireExpectedVersion(announcement, expectedVersion)
        if (announcement.status == Announcement.Status.ARCHIVED || announcement.status == Announcement.Status.EXPIRED) {
            throw ParamErrorException("已归档或已过期公告请复制为新草稿后再发布")
        }
        validateCommand(
            AnnouncementService.CreateCommand(
                title = announcement.title,
                summary = announcement.summary,
                content = announcement.content,
                type = announcement.type,
                priority = announcement.priority,
                publicHistory = announcement.publicHistory,
                autoShowEnabled = announcement.autoShowEnabled,
                autoShowMode = announcement.autoShowMode,
                autoShowCooldownHours = announcement.autoShowCooldownHours,
                actionUrl = announcement.actionUrl,
                publishedAt = announcement.publishedAt,
                effectiveFrom = announcement.effectiveFrom,
                effectiveUntil = announcement.effectiveUntil,
            ),
        )
        val before = snapshot(announcement)
        val currentTime = now()
        announcement.status = lifecycleStatus(announcement.effectiveFrom, announcement.effectiveUntil, currentTime)
        if (announcement.publishedAt == null) announcement.publishedAt = currentTime
        announcement.updatedBy = adminId
        val saved = announcementRepository.saveAndFlush(announcement)
        appendAudit(saved, adminId, AnnouncementAuditLog.Action.PUBLISHED, before, snapshot(saved), null)
        return saved
    }

    @Transactional
    override fun offline(
        adminId: Long,
        announcementId: Long,
        expectedVersion: Long,
        reason: String,
    ): Announcement? {
        adminAccessService.requireAdmin(adminId)
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return null
        requireExpectedVersion(announcement, expectedVersion)
        if (announcement.status !in setOf(Announcement.Status.SCHEDULED, Announcement.Status.PUBLISHED)) {
            throw ParamErrorException("只有已排期或已发布公告可以下线")
        }
        val normalizedReason = reason.trim().takeIf { it.isNotEmpty() }
            ?: throw ParamErrorException("下线原因不能为空")
        val before = snapshot(announcement)
        announcement.status = Announcement.Status.OFFLINE
        announcement.updatedBy = adminId
        val saved = announcementRepository.saveAndFlush(announcement)
        appendAudit(saved, adminId, AnnouncementAuditLog.Action.OFFLINE, before, snapshot(saved), normalizedReason)
        return saved
    }

    @Transactional
    override fun archive(
        adminId: Long,
        announcementId: Long,
        expectedVersion: Long,
        publicHistory: Boolean,
    ): Announcement? {
        adminAccessService.requireAdmin(adminId)
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return null
        requireExpectedVersion(announcement, expectedVersion)
        if (announcement.status == Announcement.Status.ARCHIVED) {
            throw ParamErrorException("公告已经归档")
        }
        val before = snapshot(announcement)
        announcement.status = Announcement.Status.ARCHIVED
        announcement.publicHistory = publicHistory
        announcement.archivedAt = now()
        announcement.updatedBy = adminId
        val saved = announcementRepository.saveAndFlush(announcement)
        appendAudit(saved, adminId, AnnouncementAuditLog.Action.ARCHIVED, before, snapshot(saved), null)
        return saved
    }

    @Transactional
    override fun copy(adminId: Long, announcementId: Long, expectedVersion: Long): Announcement? {
        adminAccessService.requireAdmin(adminId)
        val source = announcementRepository.findById(announcementId).orElse(null) ?: return null
        requireExpectedVersion(source, expectedVersion)
        val clone = Announcement(
            title = "${source.title}（副本）".take(120),
            summary = source.summary,
            content = source.content,
            type = source.type,
            priority = source.priority,
            status = Announcement.Status.DRAFT,
            publicHistory = source.publicHistory,
            autoShowEnabled = false,
            autoShowMode = source.autoShowMode,
            autoShowCooldownHours = source.autoShowCooldownHours,
            channel = source.channel,
            actionUrl = source.actionUrl,
            effectiveFrom = now(),
            effectiveUntil = null,
            createdBy = adminId,
            updatedBy = adminId,
        )
        val saved = announcementRepository.saveAndFlush(clone)
        appendAudit(source, adminId, AnnouncementAuditLog.Action.COPIED, snapshot(source), snapshot(source), "已复制为公告 #${saved.id}")
        appendAudit(saved, adminId, AnnouncementAuditLog.Action.CREATED, null, snapshot(saved), "由公告 #${source.id} 复制")
        return saved
    }

    @Transactional
    override fun auditLogs(
        adminId: Long,
        announcementId: Long,
        page: Int,
        size: Int,
    ): Page<AnnouncementAuditLog>? {
        adminAccessService.requireAdmin(adminId)
        if (!announcementRepository.existsById(announcementId)) return null
        return announcementAuditLogRepository.findAllByAnnouncementIdOrderByCreatedAtDescIdDesc(
            announcementId,
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)),
        )
    }

    @Transactional
    override fun currentForCustomer(
        userId: Long?,
        includeRead: Boolean,
        limit: Int,
    ): List<AnnouncementService.AudienceItem> {
        synchronizeLifecycle()
        val currentTime = now()
        val pageable = PageRequest.of(0, limit.coerceIn(1, 50))
        val announcements = if (!includeRead && userId != null) {
            announcementRepository.findCurrentUnreadForUser(
                channel = Announcement.Channel.CUSTOMER_WEB,
                statuses = listOf(Announcement.Status.PUBLISHED),
                now = currentTime,
                userId = userId,
                pageable = pageable,
            )
        } else {
            announcementRepository.findCurrent(
                channel = Announcement.Channel.CUSTOMER_WEB,
                statuses = listOf(Announcement.Status.PUBLISHED),
                now = currentTime,
                pageable = pageable,
            )
        }
        return toAudienceItems(userId, announcements)
    }

    @Transactional
    override fun autoShowForCustomer(
        userId: Long?,
        excludedIds: Collection<Long>,
    ): AnnouncementService.AudienceItem? {
        synchronizeLifecycle()
        val currentTime = now()
        val excluded = excludedIds.filter { it > 0 }.toSet()
        var page = 0
        while (true) {
            val candidates = announcementRepository.findAutoShowCandidates(
                channel = Announcement.Channel.CUSTOMER_WEB,
                statuses = listOf(Announcement.Status.PUBLISHED),
                preferredTypes = listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
                now = currentTime,
                pageable = PageRequest.of(page, AUTO_SHOW_PAGE_SIZE),
            )
            if (candidates.isEmpty()) return null
            val states = statesFor(userId, candidates)
            candidates.firstNotNullOfOrNull { announcement ->
                val id = requireNotNull(announcement.id)
                val state = states[id]
                if (id !in excluded && isAutoShowEligible(announcement, state, currentTime)) {
                    AnnouncementService.AudienceItem(announcement, state)
                } else {
                    null
                }
            }?.let { return it }
            if (candidates.size < AUTO_SHOW_PAGE_SIZE) return null
            page += 1
        }
    }

    @Transactional
    override fun getForCustomer(
        userId: Long?,
        announcementId: Long,
    ): AnnouncementService.AudienceItem? {
        synchronizeLifecycle()
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return null
        val currentTime = now()
        if (!isCustomerVisible(announcement, currentTime)) return null
        return AnnouncementService.AudienceItem(
            announcement,
            userId?.let { announcementUserStateRepository.findByAnnouncementIdAndUserId(announcementId, it) },
        )
    }

    @Transactional
    override fun historyForCustomer(
        userId: Long?,
        query: AnnouncementService.HistoryQuery,
    ): Page<AnnouncementService.AudienceItem> {
        synchronizeLifecycle()
        val currentTime = now()
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
        val specification = Specification<Announcement> { root, _, criteriaBuilder ->
            val predicates = mutableListOf<Predicate>()
            predicates += criteriaBuilder.isTrue(root.get("publicHistory"))
            predicates += criteriaBuilder.isNotNull(root.get<LocalDateTime>("publishedAt"))
            predicates += criteriaBuilder.or(
                criteriaBuilder.equal(root.get<Announcement.Status>("status"), Announcement.Status.OFFLINE),
                criteriaBuilder.equal(root.get<Announcement.Status>("status"), Announcement.Status.EXPIRED),
                criteriaBuilder.equal(root.get<Announcement.Status>("status"), Announcement.Status.ARCHIVED),
                criteriaBuilder.lessThanOrEqualTo(root.get("effectiveUntil"), currentTime),
            )
            query.type?.let { predicates += criteriaBuilder.equal(root.get<Announcement.Type>("type"), it) }
            query.year?.let { year ->
                val start = LocalDateTime.of(year, 1, 1, 0, 0)
                val end = start.plusYears(1)
                predicates += criteriaBuilder.greaterThanOrEqualTo(root.get("publishedAt"), start)
                predicates += criteriaBuilder.lessThan(root.get("publishedAt"), end)
            }
            keyword?.let { value ->
                val escaped = value.lowercase()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                val pattern = "%$escaped%"
                predicates += criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern, '\\'),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("summary")), pattern, '\\'),
                )
            }
            criteriaBuilder.and(*predicates.toTypedArray())
        }
        val pageable = PageRequest.of(
            query.page.coerceAtLeast(0),
            query.size.coerceIn(1, 50),
            Sort.by(Sort.Direction.DESC, "publishedAt").and(Sort.by(Sort.Direction.DESC, "id")),
        )
        val page = announcementRepository.findAll(specification, pageable)
        val states = statesFor(userId, page.content)
        val items = page.content.map { announcement ->
            AnnouncementService.AudienceItem(announcement, states[requireNotNull(announcement.id)])
        }
        return PageImpl(items, pageable, page.totalElements)
    }

    @Transactional
    override fun recordCustomerState(
        userId: Long,
        announcementId: Long,
        state: AnnouncementUserState.State,
    ): AnnouncementUserState? {
        synchronizeLifecycle()
        userRepository.findByIdForUpdate(userId) ?: return null
        val announcement = announcementRepository.findById(announcementId).orElse(null) ?: return null
        val currentTime = now()
        if (!isCustomerVisible(announcement, currentTime)) return null
        val existing = announcementUserStateRepository.findByAnnouncementIdAndUserId(announcementId, userId)
        val userState = existing ?: AnnouncementUserState(
            announcementId = announcementId,
            userId = userId,
            state = state,
            firstSeenAt = currentTime,
            lastSeenAt = currentTime,
        )
        userState.lastSeenAt = currentTime
        userState.state = preferredState(userState.state, state)
        if (state == AnnouncementUserState.State.DISMISSED) userState.dismissedAt = currentTime
        if (state == AnnouncementUserState.State.ACKNOWLEDGED) userState.acknowledgedAt = currentTime
        return announcementUserStateRepository.saveAndFlush(userState)
    }

    @Transactional
    override fun synchronizeLifecycle(): Int {
        val currentTime = now()
        val expired = announcementRepository.findDueForExpiration(
            currentTime,
            listOf(Announcement.Status.SCHEDULED, Announcement.Status.PUBLISHED),
        )
        expired.forEach { announcement ->
            val before = snapshot(announcement)
            announcement.status = Announcement.Status.EXPIRED
            announcement.updatedBy = SYSTEM_OPERATOR_ID
            val saved = announcementRepository.saveAndFlush(announcement)
            appendAudit(
                saved,
                SYSTEM_OPERATOR_ID,
                AnnouncementAuditLog.Action.SYSTEM_EXPIRED,
                before,
                snapshot(saved),
                "公告到达失效时间，系统自动结束",
            )
        }
        val published = announcementRepository.findDueForPublication(currentTime, Announcement.Status.SCHEDULED)
        published.forEach { announcement ->
            val before = snapshot(announcement)
            announcement.status = Announcement.Status.PUBLISHED
            if (announcement.publishedAt == null) announcement.publishedAt = currentTime
            announcement.updatedBy = SYSTEM_OPERATOR_ID
            val saved = announcementRepository.saveAndFlush(announcement)
            appendAudit(
                saved,
                SYSTEM_OPERATOR_ID,
                AnnouncementAuditLog.Action.SYSTEM_PUBLISHED,
                before,
                snapshot(saved),
                "公告到达生效时间，系统自动发布",
            )
        }
        return expired.size + published.size
    }

    private fun toAudienceItems(
        userId: Long?,
        announcements: List<Announcement>,
    ): List<AnnouncementService.AudienceItem> {
        val states = statesFor(userId, announcements)
        return announcements.map { announcement ->
            AnnouncementService.AudienceItem(announcement, states[requireNotNull(announcement.id)])
        }
    }

    private fun statesFor(
        userId: Long?,
        announcements: Collection<Announcement>,
    ): Map<Long, AnnouncementUserState> {
        if (userId == null || announcements.isEmpty()) return emptyMap()
        val ids = announcements.mapNotNull(Announcement::id)
        return announcementUserStateRepository.findAllByUserIdAndAnnouncementIdIn(userId, ids)
            .associateBy(AnnouncementUserState::announcementId)
    }

    private fun isAutoShowEligible(
        announcement: Announcement,
        state: AnnouncementUserState?,
        currentTime: LocalDateTime,
    ): Boolean {
        return when (announcement.autoShowMode) {
            Announcement.AutoShowMode.EVERY_LOAD,
            Announcement.AutoShowMode.ONCE_PER_BROWSER_SESSION,
            -> true
            Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT -> state == null
            Announcement.AutoShowMode.COOLDOWN -> {
                val previous = state?.lastSeenAt
                previous == null || previous.plusHours(requireNotNull(announcement.autoShowCooldownHours).toLong()) <= currentTime
            }
        }
    }

    private fun isCustomerVisible(announcement: Announcement, currentTime: LocalDateTime): Boolean {
        val effectiveUntil = announcement.effectiveUntil
        val current = announcement.status == Announcement.Status.PUBLISHED &&
            announcement.effectiveFrom <= currentTime &&
            (effectiveUntil == null || effectiveUntil > currentTime)
        val history = announcement.publicHistory && announcement.publishedAt != null && !current &&
            (
                announcement.status in setOf(
                    Announcement.Status.OFFLINE,
                    Announcement.Status.EXPIRED,
                    Announcement.Status.ARCHIVED,
                ) || (effectiveUntil != null && effectiveUntil <= currentTime)
            )
        return current || history
    }

    private fun validateCommand(command: AnnouncementService.CreateCommand) {
        if (command.title.trim().isEmpty()) throw ParamErrorException("公告标题不能为空")
        if (command.summary.trim().isEmpty()) throw ParamErrorException("公告摘要不能为空")
        if (command.content.trim().isEmpty()) throw ParamErrorException("公告正文不能为空")
        if (command.priority !in 0..100) throw ParamErrorException("公告优先级必须在 0 到 100 之间")
        if (command.effectiveUntil != null && command.effectiveUntil <= command.effectiveFrom) {
            throw ParamErrorException("失效时间必须晚于生效时间")
        }
        if (command.autoShowEnabled && !command.publicHistory) {
            throw ParamErrorException("开启主动展示的公告必须允许公开留存历史")
        }
        normalizedCooldown(command.autoShowMode, command.autoShowCooldownHours)
        normalizedActionUrl(command.actionUrl)
    }

    private fun normalizedCooldown(
        mode: Announcement.AutoShowMode,
        cooldownHours: Int?,
    ): Int? = when (mode) {
        Announcement.AutoShowMode.COOLDOWN -> cooldownHours?.takeIf { it in 1..(24 * 30) }
            ?: throw ParamErrorException("冷却展示模式必须设置 1 到 720 小时的冷却时间")
        else -> null
    }

    private fun normalizedActionUrl(actionUrl: String?): String? {
        val normalized = actionUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (containsUnsafeUrlCharacters(normalized)) throw ParamErrorException("公告链接包含不安全字符")
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw ParamErrorException("公告链接格式无效")
        if (!uri.isAbsolute) {
            val rawPath = uri.rawPath ?: throw ParamErrorException("公告站内链接必须以单个 / 开头")
            if (uri.authority != null || !rawPath.startsWith("/") || rawPath.startsWith("//")) {
                throw ParamErrorException("公告站内链接必须以单个 / 开头")
            }
            ensureDecodedPathIsSafe(rawPath)
            return normalized
        }
        if (
            !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null
        ) {
            throw ParamErrorException("公告站外链接只允许使用 HTTPS")
        }
        uri.rawPath?.let(::ensureDecodedPathIsSafe)
        return normalized
    }

    private fun ensureDecodedPathIsSafe(rawPath: String) {
        var decoded = rawPath
        repeat(3) {
            if (containsUnsafeUrlCharacters(decoded) || decoded.startsWith("//")) {
                throw ParamErrorException("公告链接包含不安全路径")
            }
            val next = runCatching { URLDecoder.decode(decoded, StandardCharsets.UTF_8) }.getOrElse {
                throw ParamErrorException("公告链接格式无效")
            }
            if (next == decoded) return
            decoded = next
        }
        if (containsUnsafeUrlCharacters(decoded) || decoded.startsWith("//")) {
            throw ParamErrorException("公告链接包含不安全路径")
        }
    }

    private fun containsUnsafeUrlCharacters(value: String): Boolean = value.any {
        it == '\\' || it.code in 0..31 || it.code == 127
    }

    private fun lifecycleStatus(
        effectiveFrom: LocalDateTime,
        effectiveUntil: LocalDateTime?,
        currentTime: LocalDateTime,
    ): Announcement.Status {
        if (effectiveUntil != null && effectiveUntil <= currentTime) {
            throw ParamErrorException("失效时间已到，不能发布公告")
        }
        return if (effectiveFrom > currentTime) Announcement.Status.SCHEDULED else Announcement.Status.PUBLISHED
    }

    private fun requireExpectedVersion(announcement: Announcement, expectedVersion: Long) {
        val actualVersion = announcement.version ?: 0
        if (actualVersion != expectedVersion) throw AnnouncementVersionConflictException(actualVersion)
    }

    private fun preferredState(
        current: AnnouncementUserState.State,
        requested: AnnouncementUserState.State,
    ): AnnouncementUserState.State {
        val currentWeight = when (current) {
            AnnouncementUserState.State.SEEN -> 1
            AnnouncementUserState.State.DISMISSED -> 2
            AnnouncementUserState.State.ACKNOWLEDGED -> 3
        }
        val requestedWeight = when (requested) {
            AnnouncementUserState.State.SEEN -> 1
            AnnouncementUserState.State.DISMISSED -> 2
            AnnouncementUserState.State.ACKNOWLEDGED -> 3
        }
        return if (requestedWeight >= currentWeight) requested else current
    }

    private fun appendAudit(
        announcement: Announcement,
        operatorId: Long,
        action: AnnouncementAuditLog.Action,
        beforeSnapshot: String?,
        afterSnapshot: String,
        reason: String?,
    ) {
        announcementAuditLogRepository.save(
            AnnouncementAuditLog(
                announcementId = requireNotNull(announcement.id),
                operatorId = operatorId,
                action = action,
                beforeSnapshot = beforeSnapshot,
                afterSnapshot = afterSnapshot,
                reason = reason,
            ),
        )
    }

    private fun snapshot(announcement: Announcement): String = objectMapper.writeValueAsString(
        mapOf(
            "title" to announcement.title,
            "summary" to announcement.summary,
            "content_length" to announcement.content.length,
            "type" to announcement.type.name,
            "priority" to announcement.priority,
            "status" to announcement.status.name,
            "public_history" to announcement.publicHistory,
            "auto_show_enabled" to announcement.autoShowEnabled,
            "auto_show_mode" to announcement.autoShowMode.name,
            "auto_show_cooldown_hours" to announcement.autoShowCooldownHours,
            "action_url" to announcement.actionUrl,
            "effective_from" to announcement.effectiveFrom.toString(),
            "effective_until" to announcement.effectiveUntil?.toString(),
            "published_at" to announcement.publishedAt?.toString(),
        ),
    )

    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), announcementZoneId)

    private companion object {
        const val AUTO_SHOW_PAGE_SIZE = 50
        const val SYSTEM_OPERATOR_ID = 0L
    }
}
