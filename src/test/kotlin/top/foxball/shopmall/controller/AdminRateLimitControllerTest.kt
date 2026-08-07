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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.controller.admin.AdminRateLimitController
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.ratelimit.RateLimitSettings
import top.foxball.shopmall.ratelimit.RateLimitSettingsSource
import top.foxball.shopmall.ratelimit.RateLimitSettingsUpdateResult
import top.foxball.shopmall.ratelimit.RateLimitSettingsService
import top.foxball.shopmall.ratelimit.UpdateRateLimitSettingsCommand
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

class AdminRateLimitControllerTest {
    private lateinit var settingsService: RateLimitSettingsService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        settingsService = mock(RateLimitSettingsService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminRateLimitController(settingsService, ResponseBuilder()),
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
    fun `get returns snake case rate limit settings`() {
        `when`(settingsService.getSettings(99L)).thenReturn(settings())

        mockMvc.perform(get("/admin/api/rate-limit-settings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.window_seconds").value(60))
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.authenticated_requests_per_minute").value(10))
            .andExpect(jsonPath("$.data.anonymous_requests_per_minute").value(5))
            .andExpect(jsonPath("$.data.excluded_paths[0]").value("/api/catalog/**"))
            .andExpect(jsonPath("$.data.updated_at").value("2026-08-07T11:30:00"))

        verify(settingsService).getSettings(99L)
    }

    @Test
    fun `put passes complete replacement path list and returns updated snapshot`() {
        val command = UpdateRateLimitSettingsCommand(
            enabled = true,
            authenticatedRequestsPerMinute = 12,
            anonymousRequestsPerMinute = 6,
            excludedPaths = listOf("/api/catalog/**", "/api/files/public"),
            expectedVersion = 3,
        )
        val updated = settings(
            authenticated = 12,
            anonymous = 6,
            paths = command.excludedPaths,
            version = 4,
        )
        `when`(settingsService.updateSettings(99L, command)).thenReturn(RateLimitSettingsUpdateResult.Updated(updated))

        mockMvc.perform(
            put("/admin/api/rate-limit-settings")
                .param("authenticated_requests_per_minute", "12")
                .param("anonymous_requests_per_minute", "6")
                .param("enabled", "true")
                .param("excluded_path", "/api/catalog/**", "/api/files/public")
                .param("expected_version", "3"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(4))
            .andExpect(jsonPath("$.data.excluded_paths[1]").value("/api/files/public"))

        verify(settingsService).updateSettings(99L, command)
    }

    @Test
    fun `put without path parameter explicitly clears dynamic exclusions`() {
        val command = UpdateRateLimitSettingsCommand(
            enabled = true,
            authenticatedRequestsPerMinute = 10,
            anonymousRequestsPerMinute = 5,
            excludedPaths = emptyList(),
            expectedVersion = 1,
        )
        `when`(settingsService.updateSettings(99L, command)).thenReturn(
            RateLimitSettingsUpdateResult.Updated(settings(paths = emptyList(), version = 2)),
        )

        mockMvc.perform(
            put("/admin/api/rate-limit-settings")
                .param("authenticated_requests_per_minute", "10")
                .param("anonymous_requests_per_minute", "5")
                .param("enabled", "true")
                .param("expected_version", "1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.excluded_paths").isEmpty)

        verify(settingsService).updateSettings(99L, command)
    }

    @Test
    fun `put returns conflict with actual version`() {
        val command = UpdateRateLimitSettingsCommand(
            enabled = true,
            authenticatedRequestsPerMinute = 10,
            anonymousRequestsPerMinute = 5,
            excludedPaths = emptyList(),
            expectedVersion = 2,
        )
        `when`(settingsService.updateSettings(99L, command)).thenReturn(RateLimitSettingsUpdateResult.Conflict(3))

        mockMvc.perform(
            put("/admin/api/rate-limit-settings")
                .param("authenticated_requests_per_minute", "10")
                .param("anonymous_requests_per_minute", "5")
                .param("enabled", "true")
                .param("expected_version", "2"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.data.actual_version").value(3))
    }

    @Test
    fun `dedicated enabled endpoint preserves current quotas and paths`() {
        val current = settings(enabled = true, version = 3)
        val updated = current.copy(enabled = false, version = 4)
        `when`(settingsService.updateEnabled(99L, false, 3)).thenReturn(RateLimitSettingsUpdateResult.Updated(updated))

        mockMvc.perform(
            patch("/admin/api/rate-limit-settings/enabled")
                .param("enabled", "false")
                .param("expected_version", "3"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.version").value(4))

        verify(settingsService).updateEnabled(99L, false, 3)
    }

    @Test
    fun `dedicated enabled endpoint returns version conflict`() {
        `when`(settingsService.updateEnabled(99L, true, 3)).thenReturn(RateLimitSettingsUpdateResult.Conflict(4))

        mockMvc.perform(
            put("/admin/api/rate-limit-settings/enabled")
                .param("enabled", "true")
                .param("expected_version", "3"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.data.actual_version").value(4))

        verify(settingsService).updateEnabled(99L, true, 3)
    }

    private fun settings(
        authenticated: Int = 10,
        anonymous: Int = 5,
        paths: List<String> = listOf("/api/catalog/**"),
        version: Long = 3,
        enabled: Boolean = true,
    ) = RateLimitSettings(
        enabled = enabled,
        windowSeconds = 60,
        authenticatedRequestsPerMinute = authenticated,
        anonymousRequestsPerMinute = anonymous,
        excludedPaths = paths,
        version = version,
        source = RateLimitSettingsSource.REDIS,
        updatedAt = LocalDateTime.parse("2026-08-07T11:30:00"),
        updatedBy = 99,
        settingsId = "[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]",
        excludedPathsRaw = paths.joinToString("\n"),
    )
}
