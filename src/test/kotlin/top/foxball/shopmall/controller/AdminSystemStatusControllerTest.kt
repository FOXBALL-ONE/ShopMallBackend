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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.controller.admin.AdminSystemStatusController
import top.foxball.shopmall.service.AdminApplicationStatus
import top.foxball.shopmall.service.AdminDatabaseStatus
import top.foxball.shopmall.service.AdminHealthComponentStatus
import top.foxball.shopmall.service.AdminHttpStatus
import top.foxball.shopmall.service.AdminJvmStatus
import top.foxball.shopmall.service.AdminRedisStatus
import top.foxball.shopmall.service.AdminSystemResourcesStatus
import top.foxball.shopmall.service.AdminSystemStatus
import top.foxball.shopmall.service.AdminSystemStatusService
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

class AdminSystemStatusControllerTest {
    private lateinit var systemStatusService: AdminSystemStatusService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        systemStatusService = mock(AdminSystemStatusService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminSystemStatusController(systemStatusService, ResponseBuilder()),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(99L, null)
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `system status returns actuator metrics with snake case names`() {
        `when`(systemStatusService.getStatus(99L)).thenReturn(
            AdminSystemStatus(
                status = "UP",
                generatedAt = LocalDateTime.of(2026, 8, 6, 14, 0),
                collectionDurationMillis = 18,
                application = AdminApplicationStatus(
                    name = "ShopMall",
                    version = "1.0.0",
                    startedAt = LocalDateTime.of(2026, 8, 6, 13, 0),
                    uptimeSeconds = 3_600,
                ),
                system = AdminSystemResourcesStatus(
                    availableProcessors = 8,
                    systemLoadAverage = 1.25,
                    processCpuUsage = 0.12,
                    systemCpuUsage = 0.34,
                    diskTotalBytes = 1_000,
                    diskFreeBytes = 400,
                ),
                jvm = AdminJvmStatus(
                    heapUsedBytes = 100,
                    heapCommittedBytes = 200,
                    heapMaxBytes = 400,
                    nonHeapUsedBytes = 50,
                    liveThreads = 24,
                    peakThreads = 31,
                    daemonThreads = 18,
                    gcCollectionCount = 12,
                    gcCollectionTimeMillis = 345,
                ),
                http = AdminHttpStatus(
                    requestCount = 200,
                    activeRequests = 2,
                    serverErrorCount = 3,
                    averageDurationMillis = 12.5,
                    maxDurationMillis = 180.0,
                ),
                database = AdminDatabaseStatus(
                    status = "UP",
                    activeConnections = 2,
                    idleConnections = 8,
                    minConnections = 1,
                    maxConnections = 10,
                ),
                redis = AdminRedisStatus(status = "UP"),
                healthComponents = listOf(
                    AdminHealthComponentStatus(id = "db", status = "UP"),
                    AdminHealthComponentStatus(id = "redis", status = "UP"),
                ),
            ),
        )

        mockMvc.perform(get("/admin/api/system-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.source").value("Spring Boot Actuator"))
            .andExpect(jsonPath("$.data.generated_at").value("2026-08-06T14:00:00"))
            .andExpect(jsonPath("$.data.collection_duration_ms").value(18))
            .andExpect(jsonPath("$.data.application.uptime_seconds").value(3_600))
            .andExpect(jsonPath("$.data.system.disk_free_bytes").value(400))
            .andExpect(jsonPath("$.data.jvm.heap_used_bytes").value(100))
            .andExpect(jsonPath("$.data.http.server_error_count").value(3))
            .andExpect(jsonPath("$.data.database.active_connections").value(2))
            .andExpect(jsonPath("$.data.health_components[1].id").value("redis"))

        verify(systemStatusService).getStatus(99L)
    }
}
