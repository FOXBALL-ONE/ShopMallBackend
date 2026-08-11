package top.foxball.shopmall.repository

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState
import java.time.LocalDateTime
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnnouncementRepositoryIntegrationTest {
    @Autowired
    private lateinit var announcementRepository: AnnouncementRepository

    @Autowired
    private lateinit var announcementUserStateRepository: AnnouncementUserStateRepository

    @Test
    fun `auto show candidates use priority then preferred type weight`() {
        val currentTime = LocalDateTime.of(2026, 8, 11, 12, 0)
        val highPriorityPromotion = announcement(
            title = "High priority promotion",
            type = Announcement.Type.PROMOTION,
            priority = 100,
            effectiveFrom = currentTime.minusHours(4),
        )
        val general = announcement(
            title = "General",
            type = Announcement.Type.GENERAL,
            priority = 90,
            effectiveFrom = currentTime.minusHours(1),
        )
        val important = announcement(
            title = "Important",
            type = Announcement.Type.IMPORTANT,
            priority = 90,
            effectiveFrom = currentTime.minusHours(3),
        )
        val maintenance = announcement(
            title = "Maintenance",
            type = Announcement.Type.MAINTENANCE,
            priority = 90,
            effectiveFrom = currentTime.minusHours(2),
        )
        val disabled = announcement(
            title = "Disabled",
            type = Announcement.Type.MAINTENANCE,
            priority = 100,
            effectiveFrom = currentTime.minusHours(1),
        ).apply { autoShowEnabled = false }
        announcementRepository.saveAllAndFlush(
            listOf(general, important, maintenance, highPriorityPromotion, disabled),
        )

        val candidates = announcementRepository.findAutoShowCandidates(
            channel = Announcement.Channel.CUSTOMER_WEB,
            statuses = listOf(Announcement.Status.PUBLISHED),
            preferredTypes = listOf(Announcement.Type.MAINTENANCE, Announcement.Type.IMPORTANT),
            now = currentTime,
            pageable = PageRequest.of(0, 10),
        )

        assertEquals(
            listOf("High priority promotion", "Maintenance", "Important", "General"),
            candidates.map(Announcement::title),
        )
    }

    @Test
    fun `unread current query applies the limit after excluding read announcements`() {
        val currentTime = LocalDateTime.of(2026, 8, 11, 12, 0)
        val alreadyRead = announcement(
            title = "Already read",
            type = Announcement.Type.IMPORTANT,
            priority = 100,
            effectiveFrom = currentTime.minusHours(1),
        )
        val unread = announcement(
            title = "Unread",
            type = Announcement.Type.GENERAL,
            priority = 90,
            effectiveFrom = currentTime.minusHours(1),
        )
        announcementRepository.saveAllAndFlush(listOf(alreadyRead, unread))
        announcementUserStateRepository.saveAndFlush(
            AnnouncementUserState(
                announcementId = requireNotNull(alreadyRead.id),
                userId = 41,
                state = AnnouncementUserState.State.SEEN,
                firstSeenAt = currentTime.minusMinutes(10),
                lastSeenAt = currentTime.minusMinutes(10),
            ),
        )

        val items = announcementRepository.findCurrentUnreadForUser(
            channel = Announcement.Channel.CUSTOMER_WEB,
            statuses = listOf(Announcement.Status.PUBLISHED),
            now = currentTime,
            userId = 41,
            pageable = PageRequest.of(0, 1),
        )

        assertEquals(listOf("Unread"), items.map(Announcement::title))
    }

    private fun announcement(
        title: String,
        type: Announcement.Type,
        priority: Int,
        effectiveFrom: LocalDateTime,
    ) = Announcement(
        title = title,
        summary = "$title summary",
        content = "$title content",
        type = type,
        priority = priority,
        status = Announcement.Status.PUBLISHED,
        publicHistory = true,
        autoShowEnabled = true,
        autoShowMode = Announcement.AutoShowMode.EVERY_LOAD,
        effectiveFrom = effectiveFrom,
        effectiveUntil = effectiveFrom.plusDays(1),
        publishedAt = effectiveFrom.minusMinutes(5),
        createdBy = 99,
        updatedBy = 99,
    )
}
