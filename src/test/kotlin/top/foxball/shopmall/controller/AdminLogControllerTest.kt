package top.foxball.shopmall.controller

import kotlinx.coroutines.runBlocking
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.controller.admin.AdminLogController
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.logging.ActiveLogFile
import top.foxball.shopmall.logging.LiveLogBatch
import top.foxball.shopmall.logging.LiveLogQuery
import top.foxball.shopmall.logging.LiveLogService
import top.foxball.shopmall.logging.LogHistoryService
import top.foxball.shopmall.logging.LogLevel
import top.foxball.shopmall.logging.LoggingProperties
import top.foxball.shopmall.logging.LoggingSettings
import top.foxball.shopmall.logging.LoggingSettingsService
import top.foxball.shopmall.logging.LoggingSettingsSource
import top.foxball.shopmall.logging.LoggingSettingsUpdateResult
import top.foxball.shopmall.logging.RuntimeLoggingManager
import top.foxball.shopmall.logging.RuntimeLoggingSnapshot
import top.foxball.shopmall.logging.RuntimeLoggingStatus
import top.foxball.shopmall.logging.UpdateLoggingSettingsCommand
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

class AdminLogControllerTest {
    private lateinit var settingsService: LoggingSettingsService
    private lateinit var runtimeLoggingManager: RuntimeLoggingManager
    private lateinit var liveLogService: LiveLogService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        settingsService = mock(LoggingSettingsService::class.java)
        runtimeLoggingManager = mock(RuntimeLoggingManager::class.java)
        liveLogService = mock(LiveLogService::class.java)
        val properties = LoggingProperties(storagePath = "./build/test-logs")
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminLogController(
                settingsService,
                runtimeLoggingManager,
                properties,
                liveLogService,
                mock(LogHistoryService::class.java),
                ResponseBuilder(),
            ),
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
    fun `settings endpoint returns snake case runtime state`() {
        `when`(settingsService.getSettings(99L)).thenReturn(settings())
        `when`(runtimeLoggingManager.snapshot()).thenReturn(snapshot())

        mockMvc.perform(get("/admin/api/logs/settings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.root_level").value("INFO"))
            .andExpect(jsonPath("$.data.logger_overrides[0].logger_name").value("org.hibernate"))
            .andExpect(jsonPath("$.data.active_file").value("2026/08/08/example-0.log"))

        verify(settingsService).getSettings(99L)
    }

    @Test
    fun `update returns version conflict without applying runtime settings`() {
        val command = UpdateLoggingSettingsCommand(
            rootLevel = "INFO",
            loggerOverrides = emptyList(),
            outputTemplate = LoggingProperties.DEFAULT_OUTPUT_TEMPLATE,
            expectedVersion = 2,
        )
        `when`(settingsService.updateSettings(99L, command)).thenReturn(LoggingSettingsUpdateResult.Conflict(3))

        mockMvc.perform(
            put("/admin/api/logs/settings")
                .param("root_level", "INFO")
                .param("output_template", LoggingProperties.DEFAULT_OUTPUT_TEMPLATE)
                .param("expected_version", "2"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.data.actual_version").value(3))
    }

    @Test
    fun `live endpoint is non-cacheable and passes the selected filters`() {
        val batch = LiveLogBatch("boot", false, false, 0, 1, 4, emptyList())
        runBlocking {
            `when`(liveLogService.poll(99L, LiveLogQuery(null, null, LogLevel.WARN, "top.", "slow", 100, 0)))
                .thenReturn(batch)
        }

        val asyncResult = mockMvc.perform(
            get("/admin/api/logs/live")
                .param("minimum_level", "WARN")
                .param("logger_prefix", "top.")
                .param("query", "slow")
                .param("limit", "100")
                .param("wait_seconds", "0"),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.boot_id").value("boot"))

        runBlocking {
            verify(liveLogService).poll(99L, LiveLogQuery(null, null, LogLevel.WARN, "top.", "slow", 100, 0))
        }
    }

    private fun settings() = LoggingSettings(
        rootLevel = LogLevel.INFO,
        loggerLevels = mapOf("org.hibernate" to LogLevel.WARN),
        outputTemplate = LoggingProperties.DEFAULT_OUTPUT_TEMPLATE,
        version = 3,
        source = LoggingSettingsSource.REDIS,
        updatedAt = LocalDateTime.parse("2026-08-08T19:25:14.238"),
        updatedBy = 99,
        settingsId = "[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]-[REDACTED]",
    )

    private fun snapshot() = RuntimeLoggingSnapshot(
        effectiveVersion = 3,
        status = RuntimeLoggingStatus.UP,
        activeFile = ActiveLogFile(
            relativePath = "2026/08/08/example-0.log",
            sizeBytes = 128,
            fileTime = LocalDateTime.parse("2026-08-08T19:25:14.238"),
            rotationIndex = 0,
            lastWriteAt = null,
            lastError = null,
        ),
        lastError = null,
    )
}
