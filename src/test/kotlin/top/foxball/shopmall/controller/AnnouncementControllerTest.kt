package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState
import top.foxball.shopmall.service.AnnouncementService
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

class AnnouncementControllerTest {
    private lateinit var announcementService: AnnouncementService
    private lateinit var mockMvc: MockMvc
    private val effectiveFrom = LocalDateTime.of(2026, 8, 11, 12, 30, 15)

    @BeforeEach
    fun setUp() {
        announcementService = mock(AnnouncementService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AnnouncementController(announcementService, ResponseBuilder()),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `current announcements use snake case and ISO local date time`() {
        val announcement = announcement()
        `when`(announcementService.currentForCustomer(null, false, 1)).thenReturn(
            listOf(AnnouncementService.AudienceItem(announcement, null)),
        )

        mockMvc.perform(
            get("/api/announcements/current")
                .param("include_read", "false")
                .param("limit", "1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].id").value(18))
            .andExpect(jsonPath("$.data.items[0].auto_show_enabled").value(true))
            .andExpect(jsonPath("$.data.items[0].auto_show_mode").value("COOLDOWN"))
            .andExpect(jsonPath("$.data.items[0].auto_show_cooldown_hours").value(12))
            .andExpect(jsonPath("$.data.items[0].action_url").value("/collections/new"))
            .andExpect(jsonPath("$.data.items[0].effective_from").value("2026-08-11T12:30:15"))
            .andExpect(jsonPath("$.data.items[0].is_read").value(false))
    }

    @Test
    fun `authenticated customer can submit announcement state`() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(41L, null)
        val userState = AnnouncementUserState(
            id = 25,
            announcementId = 18,
            userId = 41,
            state = AnnouncementUserState.State.ACKNOWLEDGED,
            firstSeenAt = effectiveFrom.plusMinutes(1),
            lastSeenAt = effectiveFrom.plusMinutes(2),
            acknowledgedAt = effectiveFrom.plusMinutes(2),
        )
        `when`(
            announcementService.recordCustomerState(41, 18, AnnouncementUserState.State.ACKNOWLEDGED),
        ).thenReturn(userState)

        mockMvc.perform(
            post("/api/announcements/18/state")
                .param("state", "ACKNOWLEDGED"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.state").value("ACKNOWLEDGED"))
            .andExpect(jsonPath("$.data.first_seen_at").value("2026-08-11T12:31:15"))
            .andExpect(jsonPath("$.data.last_seen_at").value("2026-08-11T12:32:15"))
            .andExpect(jsonPath("$.data.acknowledged_at").value("2026-08-11T12:32:15"))
    }

    private fun announcement() = Announcement(
        id = 18,
        title = "Shipping update",
        summary = "Shipping windows changed.",
        content = "Details",
        type = Announcement.Type.MAINTENANCE,
        priority = 90,
        status = Announcement.Status.PUBLISHED,
        publicHistory = true,
        autoShowEnabled = true,
        autoShowMode = Announcement.AutoShowMode.COOLDOWN,
        autoShowCooldownHours = 12,
        actionUrl = "/collections/new",
        effectiveFrom = effectiveFrom,
        effectiveUntil = effectiveFrom.plusDays(1),
        publishedAt = effectiveFrom,
        createdBy = 99,
        updatedBy = 99,
    )
}
