package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.Announcement
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.service.AnnouncementService
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

class AdminAnnouncementControllerTest {
    private lateinit var announcementService: AnnouncementService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        announcementService = mock(AnnouncementService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminAnnouncementController(announcementService, ResponseBuilder()),
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(99L, null)
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `update binds stores and returns all managed announcement times`() {
        val publishedAt = LocalDateTime.of(2026, 8, 15, 8, 30, 15)
        val effectiveFrom = LocalDateTime.of(2026, 8, 16, 9, 0, 30)
        val effectiveUntil = LocalDateTime.of(2026, 8, 31, 18, 45, 45)
        val command = AnnouncementService.UpdateCommand(
            title = "Managed announcement",
            summary = "Managed summary",
            content = "Managed content",
            type = Announcement.Type.IMPORTANT,
            priority = 80,
            publicHistory = true,
            autoShowEnabled = false,
            autoShowMode = Announcement.AutoShowMode.ONCE_PER_ANNOUNCEMENT,
            autoShowCooldownHours = null,
            actionUrl = null,
            publishedAt = publishedAt,
            effectiveFrom = effectiveFrom,
            effectiveUntil = effectiveUntil,
            expectedVersion = 3,
        )
        val announcement = Announcement(
            id = 7,
            version = 4,
            title = command.title,
            summary = command.summary,
            content = command.content,
            type = command.type,
            priority = command.priority,
            status = Announcement.Status.SCHEDULED,
            publicHistory = command.publicHistory,
            autoShowEnabled = command.autoShowEnabled,
            autoShowMode = command.autoShowMode,
            publishedAt = publishedAt,
            effectiveFrom = effectiveFrom,
            effectiveUntil = effectiveUntil,
            createdBy = 99,
            updatedBy = 99,
        )
        `when`(announcementService.update(99, 7, command)).thenReturn(announcement)

        mockMvc.perform(
            put("/admin/api/announcements/7")
                .param("title", command.title)
                .param("summary", command.summary)
                .param("content", command.content)
                .param("type", command.type.name)
                .param("priority", command.priority.toString())
                .param("public_history", command.publicHistory.toString())
                .param("auto_show_enabled", command.autoShowEnabled.toString())
                .param("auto_show_mode", command.autoShowMode.name)
                .param("published_at", "2026-08-15T08:30:15")
                .param("effective_from", "2026-08-16T09:00:30")
                .param("effective_until", "2026-08-31T18:45:45")
                .param("expected_version", command.expectedVersion.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.published_at").value("2026-08-15T08:30:15"))
            .andExpect(jsonPath("$.data.effective_from").value("2026-08-16T09:00:30"))
            .andExpect(jsonPath("$.data.effective_until").value("2026-08-31T18:45:45"))

        verify(announcementService).update(99, 7, command)
    }
}
