package top.foxball.shopmall.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor
import org.springframework.boot.health.contributor.Status
import top.foxball.shopmall.service.impl.AdminSystemStatusServiceImpl
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminSystemStatusServiceImplTest {
    @Test
    fun `collects health and runtime metrics through actuator endpoints`() {
        val adminAccessService = mock(AdminAccessService::class.java)
        val healthEndpoint = mock(HealthEndpoint::class.java)
        val health = mock(CompositeHealthDescriptor::class.java)
        val databaseHealth = mock(IndicatedHealthDescriptor::class.java)
        val redisHealth = mock(IndicatedHealthDescriptor::class.java)
        val diskHealth = mock(IndicatedHealthDescriptor::class.java)
        `when`(healthEndpoint.health()).thenReturn(health)
        `when`(health.status).thenReturn(Status.UP)
        `when`(health.components).thenReturn(
            mapOf(
                "db" to databaseHealth,
                "diskSpace" to diskHealth,
                "redis" to redisHealth,
            ),
        )
        `when`(databaseHealth.status).thenReturn(Status.UP)
        `when`(redisHealth.status).thenReturn(Status.DOWN)
        `when`(diskHealth.status).thenReturn(Status.UP)

        val registry = SimpleMeterRegistry()
        registerGauge(registry, "process.uptime", 3_600.0)
        registerGauge(registry, "process.start.time", 1_775_457_600.0)
        registerGauge(registry, "system.cpu.count", 8.0)
        registerGauge(registry, "system.load.average.1m", 1.25)
        registerGauge(registry, "process.cpu.usage", 0.12)
        registerGauge(registry, "system.cpu.usage", 0.34)
        registerGauge(registry, "disk.total", 1_000.0)
        registerGauge(registry, "disk.free", 400.0)
        registerGauge(registry, "jvm.memory.used", 100.0, "area", "heap")
        registerGauge(registry, "jvm.memory.committed", 200.0, "area", "heap")
        registerGauge(registry, "jvm.memory.max", 400.0, "area", "heap")
        registerGauge(registry, "jvm.memory.used", 50.0, "area", "nonheap")
        registerGauge(registry, "jvm.threads.live", 24.0)
        registerGauge(registry, "jvm.threads.peak", 31.0)
        registerGauge(registry, "jvm.threads.daemon", 18.0)
        registerGauge(registry, "jdbc.connections.active", 2.0)
        registerGauge(registry, "jdbc.connections.idle", 8.0)
        registerGauge(registry, "jdbc.connections.min", 1.0)
        registerGauge(registry, "jdbc.connections.max", 10.0)
        registry.timer("http.server.requests", "status", "200").record(Duration.ofMillis(100))
        registry.timer("http.server.requests", "status", "500").record(Duration.ofMillis(250))
        registry.timer("http.server.requests", "status", "503").record(Duration.ofMillis(400))
        registry.timer("jvm.gc.pause").record(Duration.ofMillis(50))
        registry.timer("jvm.gc.pause").record(Duration.ofMillis(100))
        val activeRequest = registry.more().longTaskTimer("http.server.requests.active").start()

        val service = AdminSystemStatusServiceImpl(
            adminAccessService = adminAccessService,
            healthEndpoint = healthEndpoint,
            meterRegistry = registry,
            applicationName = "ShopMall",
        )

        val status = service.getStatus(99L)

        assertEquals("UP", status.status)
        assertEquals(3_600, status.application.uptimeSeconds)
        assertNotNull(status.application.startedAt)
        assertEquals(8, status.system.availableProcessors)
        assertEquals(0.12, status.system.processCpuUsage)
        assertEquals(400, status.system.diskFreeBytes)
        assertEquals(100, status.jvm.heapUsedBytes)
        assertEquals(2, status.jvm.gcCollectionCount)
        assertEquals(150, status.jvm.gcCollectionTimeMillis)
        assertEquals(3, status.http.requestCount)
        assertEquals(1, status.http.activeRequests)
        assertEquals(2, status.http.serverErrorCount)
        assertEquals(400.0, status.http.maxDurationMillis)
        assertTrue(status.http.averageDurationMillis!! > 249.9)
        assertEquals("UP", status.database.status)
        assertEquals(2, status.database.activeConnections)
        assertEquals("DOWN", status.redis.status)
        assertEquals(listOf("db", "diskSpace", "redis"), status.healthComponents.map { it.id })
        verify(adminAccessService).requireAdmin(99L)
        activeRequest.stop()
    }

    private fun registerGauge(
        registry: SimpleMeterRegistry,
        name: String,
        value: Double,
        vararg tags: String,
    ) {
        Gauge.builder(name) { value }
            .tags(*tags)
            .register(registry)
    }
}
