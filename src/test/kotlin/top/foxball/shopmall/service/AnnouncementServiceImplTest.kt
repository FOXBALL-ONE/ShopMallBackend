package top.foxball.shopmall.service

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import tools.jackson.databind.ObjectMapper
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.entity.jdbc.AnnouncementAuditLog
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.AnnouncementVersionConflictException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.AnnouncementAuditLogRepository
import top.foxball.shopmall.repository.AnnouncementRepository
import top.foxball.shopmall.repository.AnnouncementUserStateRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.AnnouncementServiceImpl
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnnouncementServiceImplTest {
    private val currentTime = LocalDateTime.of(2026, 8, 11, 12, 0)
    private val announcementRepository = mock(AnnouncementRepository::class.java)
    private val userStateRepository = mock(AnnouncementUserStateRepository::class.java)
    private val auditLogRepository = mock(AnnouncementAuditLogRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val lockedUser = mock(User::class.java)
    private val adminAccessService = mock(AdminAccessService::class.java)
    private val objectMapper = mock(ObjectMapper::class.java)
    private val service = AnnouncementServiceImpl(
        announcementRepository = announcementRepository,
        announcementUserStateRepository = userStateRepository,
        announcementAuditLogRepository = auditLogRepository,
        userRepository = userRepository,
        adminAccessService = adminAccessService,
        clock = Clock.fixed(currentTime.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
        objectMapper = objectMapper,
        announcementTimeZone = "UTC",
    )

    init {
        `when`(userRepository.findByIdForUpdate(anyLong())).thenReturn(lockedUser)
    }

    @Test
    fun `auto show skips an announcement already seen in once mode`() {
        val alreadySeen = announcement(1, 100, Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT)
        val fallback = announcement(2, 80, Announcement.AutoShowMode.EVERY_LOAD)
        val state = AnnouncementUserState(
            announcementId = 1,
            userId = 41,
            state = AnnouncementUserState.State.SEEN,
            firstSeenAt = currentTime.minusDays(1),
            lastSeenAt = currentTime.minusDays(1),
        )
        `when`(
            announcementRepository.findAutoShowCandidates(
                Announcement.Channel.CUSTOMER_WEB,
                listOf(Announcement.Status.PUBLISHED),
                listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
                currentTime,
                PageRequest.of(0, 50),
            ),
        ).thenReturn(listOf(alreadySeen, fallback))
        `when`(userStateRepository.findAllByUserIdAndAnnouncementIdIn(41, listOf(1, 2)))
            .thenReturn(listOf(state))

        val result = service.autoShowForCustomer(41, emptyList())

        assertEquals(2, result?.announcement?.id)
        assertNull(result?.userState)
    }

    @Test
    fun `auto show cooldown becomes eligible after the configured hours`() {
        val cooldown = announcement(1, 100, Announcement.AutoShowMode.COOLDOWN).apply {
            autoShowCooldownHours = 4
        }
        val fallback = announcement(2, 80, Announcement.AutoShowMode.EVERY_LOAD)
        val recentState = AnnouncementUserState(
            announcementId = 1,
            userId = 41,
            firstSeenAt = currentTime.minusHours(8),
            lastSeenAt = currentTime.minusHours(2),
        )
        val cooledDownState = AnnouncementUserState(
            announcementId = 1,
            userId = 41,
            firstSeenAt = currentTime.minusHours(8),
            lastSeenAt = currentTime.minusHours(4),
        )
        `when`(
            announcementRepository.findAutoShowCandidates(
                Announcement.Channel.CUSTOMER_WEB,
                listOf(Announcement.Status.PUBLISHED),
                listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
                currentTime,
                PageRequest.of(0, 50),
            ),
        ).thenReturn(listOf(cooldown, fallback))
        `when`(userStateRepository.findAllByUserIdAndAnnouncementIdIn(41, listOf(1, 2)))
            .thenReturn(listOf(recentState), listOf(cooledDownState))

        assertEquals(2, service.autoShowForCustomer(41, emptyList())?.announcement?.id)
        assertEquals(1, service.autoShowForCustomer(41, emptyList())?.announcement?.id)
    }

    @Test
    fun `auto show honors excluded ids before selecting the next priority`() {
        val highPriority = announcement(1, 100, Announcement.AutoShowMode.EVERY_LOAD)
        val nextPriority = announcement(2, 90, Announcement.AutoShowMode.EVERY_LOAD)
        `when`(
            announcementRepository.findAutoShowCandidates(
                Announcement.Channel.CUSTOMER_WEB,
                listOf(Announcement.Status.PUBLISHED),
                listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
                currentTime,
                PageRequest.of(0, 50),
            ),
        ).thenReturn(listOf(highPriority, nextPriority))

        val result = service.autoShowForCustomer(null, listOf(-1, 1))

        assertEquals(2, result?.announcement?.id)
    }

    @Test
    fun `customer state is idempotent and never downgraded`() {
        val announcement = announcement(8, 50, Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT)
        val dismissedAt = currentTime.minusHours(3)
        val existing = AnnouncementUserState(
            id = 12,
            announcementId = 8,
            userId = 41,
            state = AnnouncementUserState.State.DISMISSED,
            firstSeenAt = currentTime.minusDays(2),
            lastSeenAt = currentTime.minusHours(3),
            dismissedAt = dismissedAt,
        )
        `when`(announcementRepository.findById(8)).thenReturn(Optional.of(announcement))
        `when`(userStateRepository.findByAnnouncementIdAndUserId(8, 41)).thenReturn(existing)
        `when`(userStateRepository.saveAndFlush(any(AnnouncementUserState::class.java))).thenAnswer {
            it.arguments[0] as AnnouncementUserState
        }

        val seen = service.recordCustomerState(41, 8, AnnouncementUserState.State.SEEN)

        assertNotNull(seen)
        assertEquals(AnnouncementUserState.State.DISMISSED, seen.state)
        assertEquals(currentTime, seen.lastSeenAt)
        assertEquals(dismissedAt, seen.dismissedAt)

        val acknowledged = service.recordCustomerState(41, 8, AnnouncementUserState.State.ACKNOWLEDGED)

        assertNotNull(acknowledged)
        assertEquals(AnnouncementUserState.State.ACKNOWLEDGED, acknowledged.state)
        assertEquals(currentTime, acknowledged.acknowledgedAt)
    }

    @Test
    fun `stale expected version reports the current version before updating`() {
        val draft = announcement(7, 20, Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT).apply {
            version = 4
            status = Announcement.Status.DRAFT
        }
        `when`(announcementRepository.findById(7)).thenReturn(Optional.of(draft))
        val command = AnnouncementService.UpdateCommand(
            title = "Updated title",
            summary = "Updated summary",
            content = "Updated content",
            type = Announcement.Type.IMPORTANT,
            priority = 70,
            publicHistory = true,
            autoShowEnabled = false,
            autoShowMode = Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT,
            autoShowCooldownHours = null,
            actionUrl = null,
            publishedAt = null,
            effectiveFrom = currentTime,
            effectiveUntil = currentTime.plusDays(1),
            expectedVersion = 3,
        )

        val error = assertFailsWith<AnnouncementVersionConflictException> {
            service.update(99, 7, command)
        }

        assertEquals(4, error.actualVersion)
        verify(adminAccessService).requireAdmin(99)
        verify(announcementRepository, never()).saveAndFlush(draft)
    }

    @Test
    fun `admin update stores all managed times and can reschedule an expired announcement`() {
        val expired = announcement(9, 50, Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT).apply {
            version = 6
            status = Announcement.Status.EXPIRED
            effectiveUntil = currentTime
        }
        val managedPublishedAt = currentTime.minusDays(2)
        val managedEffectiveFrom = currentTime.plusHours(3)
        val managedEffectiveUntil = currentTime.plusDays(2)
        `when`(announcementRepository.findById(9)).thenReturn(Optional.of(expired))
        `when`(announcementRepository.saveAndFlush(any(Announcement::class.java))).thenAnswer {
            it.arguments[0] as Announcement
        }
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        val updated = service.update(
            99,
            9,
            AnnouncementService.UpdateCommand(
                title = expired.title,
                summary = expired.summary,
                content = expired.content,
                type = expired.type,
                priority = expired.priority,
                publicHistory = expired.publicHistory,
                autoShowEnabled = expired.autoShowEnabled,
                autoShowMode = expired.autoShowMode,
                autoShowCooldownHours = expired.autoShowCooldownHours,
                actionUrl = expired.actionUrl,
                publishedAt = managedPublishedAt,
                effectiveFrom = managedEffectiveFrom,
                effectiveUntil = managedEffectiveUntil,
                expectedVersion = 6,
            ),
        )

        assertNotNull(updated)
        assertEquals(managedPublishedAt, updated.publishedAt)
        assertEquals(managedEffectiveFrom, updated.effectiveFrom)
        assertEquals(managedEffectiveUntil, updated.effectiveUntil)
        assertEquals(Announcement.Status.SCHEDULED, updated.status)
        verify(announcementRepository).saveAndFlush(expired)
        verify(auditLogRepository).save(any(AnnouncementAuditLog::class.java))
    }


    @Test
    fun `auto show searches the next page when the first page has no eligible announcement`() {
        val firstPage = (1L..50L).map { announcement(it, 100, Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT) }
        val fallback = announcement(51, 90, Announcement.AutoShowMode.EVERY_LOAD)
        val states = firstPage.map { item ->
            AnnouncementUserState(
                announcementId = requireNotNull(item.id),
                userId = 41,
                state = AnnouncementUserState.State.SEEN,
                firstSeenAt = currentTime.minusDays(1),
                lastSeenAt = currentTime.minusDays(1),
            )
        }
        `when`(
            announcementRepository.findAutoShowCandidates(
                Announcement.Channel.CUSTOMER_WEB,
                listOf(Announcement.Status.PUBLISHED),
                listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
                currentTime,
                PageRequest.of(0, 50),
            ),
        ).thenReturn(firstPage)
        `when`(
            announcementRepository.findAutoShowCandidates(
                Announcement.Channel.CUSTOMER_WEB,
                listOf(Announcement.Status.PUBLISHED),
                listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
                currentTime,
                PageRequest.of(1, 50),
            ),
        ).thenReturn(listOf(fallback))
        `when`(userStateRepository.findAllByUserIdAndAnnouncementIdIn(41, firstPage.map { requireNotNull(it.id) }))
            .thenReturn(states)
        `when`(userStateRepository.findAllByUserIdAndAnnouncementIdIn(41, listOf(51)))
            .thenReturn(emptyList())

        assertEquals(51, service.autoShowForCustomer(41, emptyList())?.announcement?.id)
    }

    @Test
    fun `customer state serializes concurrent creation through the user row lock`() {
        val announcement = announcement(8, 50, Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT)
        `when`(announcementRepository.findById(8)).thenReturn(Optional.of(announcement))
        `when`(userStateRepository.findByAnnouncementIdAndUserId(8, 41)).thenReturn(null)
        `when`(userStateRepository.saveAndFlush(any(AnnouncementUserState::class.java))).thenAnswer {
            it.arguments[0] as AnnouncementUserState
        }

        service.recordCustomerState(41, 8, AnnouncementUserState.State.SEEN)

        verify(userRepository).findByIdForUpdate(41)
    }

    @Test
    fun `lifecycle transitions are persisted individually and audited as system operations`() {
        val expired = announcement(1, 50, Announcement.AutoShowMode.EVERY_LOAD).apply {
            status = Announcement.Status.PUBLISHED
            effectiveUntil = currentTime
        }
        val scheduled = announcement(2, 50, Announcement.AutoShowMode.EVERY_LOAD).apply {
            status = Announcement.Status.SCHEDULED
            publishedAt = null
        }
        `when`(
            announcementRepository.findDueForExpiration(
                currentTime,
                listOf(Announcement.Status.SCHEDULED, Announcement.Status.PUBLISHED),
            ),
        ).thenReturn(listOf(expired))
        `when`(announcementRepository.findDueForPublication(currentTime, Announcement.Status.SCHEDULED))
            .thenReturn(listOf(scheduled))
        `when`(announcementRepository.saveAndFlush(any(Announcement::class.java))).thenAnswer {
            it.arguments[0] as Announcement
        }
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        assertEquals(2, service.synchronizeLifecycle())

        assertEquals(Announcement.Status.EXPIRED, expired.status)
        assertEquals(0, expired.updatedBy)
        assertEquals(Announcement.Status.PUBLISHED, scheduled.status)
        assertEquals(currentTime, scheduled.publishedAt)
        assertEquals(0, scheduled.updatedBy)
        verify(announcementRepository, times(2)).saveAndFlush(any(Announcement::class.java))
        verify(auditLogRepository, times(2)).save(any(AnnouncementAuditLog::class.java))
    }

    @Test
    fun `deleting announcements uses an action accepted by legacy audit constraint`() {
        val first = announcement(1, 50, Announcement.AutoShowMode.EVERY_LOAD)
        val second = announcement(2, 40, Announcement.AutoShowMode.EVERY_LOAD)
        `when`(announcementRepository.findAllById(listOf(1, 2))).thenReturn(listOf(first, second))
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        assertEquals(listOf(1L, 2L), service.deleteBatch(99, listOf(2, 1)))

        val captor = ArgumentCaptor.forClass(AnnouncementAuditLog::class.java)
        verify(auditLogRepository, times(2)).save(captor.capture())
        assertEquals(listOf(AnnouncementAuditLog.Action.ARCHIVED, AnnouncementAuditLog.Action.ARCHIVED), captor.allValues.map { it.action })
        assertEquals(listOf("管理员批量删除公告", "管理员批量删除公告"), captor.allValues.map { it.reason })
    }

    @Test
    fun `announcement time is evaluated in the configured business zone`() {
        val shanghaiTime = LocalDateTime.of(2026, 8, 11, 20, 0)
        val zonedService = AnnouncementServiceImpl(
            announcementRepository = announcementRepository,
            announcementUserStateRepository = userStateRepository,
            announcementAuditLogRepository = auditLogRepository,
            userRepository = userRepository,
            adminAccessService = adminAccessService,
            clock = Clock.fixed(currentTime.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            objectMapper = objectMapper,
            announcementTimeZone = "Asia/Shanghai",
        )
        val candidate = announcement(1, 50, Announcement.AutoShowMode.EVERY_LOAD)
        `when`(
            announcementRepository.findAutoShowCandidates(
                Announcement.Channel.CUSTOMER_WEB,
                listOf(Announcement.Status.PUBLISHED),
                listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
                shanghaiTime,
                PageRequest.of(0, 50),
            ),
        ).thenReturn(listOf(candidate))

        assertEquals(1, zonedService.autoShowForCustomer(null, emptyList())?.announcement?.id)
    }

    @Test
    fun `unsafe action URLs are rejected before announcement creation`() {
        listOf("//evil.example", "/\\evil.example", "/%5C%5Cevil.example", "/%2F%2Fevil.example", "http://evil.example").forEach { actionUrl ->
            assertFailsWith<ParamErrorException> { service.create(99, createCommand(actionUrl)) }
        }
    }

    @Test
    fun `safe internal and HTTPS action URLs are accepted`() {
        listOf("/collections/new?source=notice", "https://example.com/notice").forEach { actionUrl ->
            `when`(announcementRepository.saveAndFlush(any(Announcement::class.java))).thenAnswer {
                (it.arguments[0] as Announcement).apply { id = 100 }
            }
            `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")
            assertNotNull(service.create(99, createCommand(actionUrl)))
        }
    }

    private fun createCommand(actionUrl: String) = AnnouncementService.CreateCommand(
        title = "Notice",
        summary = "Summary",
        content = "Content",
        type = Announcement.Type.GENERAL,
        priority = 50,
        publicHistory = true,
        autoShowEnabled = false,
        autoShowMode = Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT,
        autoShowCooldownHours = null,
        actionUrl = actionUrl,
        publishedAt = null,
        effectiveFrom = currentTime,
        effectiveUntil = currentTime.plusDays(1),
    )

    private fun announcement(
        id: Long,
        priority: Int,
        autoShowMode: Announcement.AutoShowMode,
    ) = Announcement(
        id = id,
        title = "Notice $id",
        summary = "Summary $id",
        content = "Content $id",
        priority = priority,
        status = Announcement.Status.PUBLISHED,
        publicHistory = true,
        autoShowEnabled = true,
        autoShowMode = autoShowMode,
        effectiveFrom = currentTime.minusHours(1),
        effectiveUntil = currentTime.plusDays(1),
        publishedAt = currentTime.minusHours(1),
        createdBy = 99,
        updatedBy = 99,
    )
}
