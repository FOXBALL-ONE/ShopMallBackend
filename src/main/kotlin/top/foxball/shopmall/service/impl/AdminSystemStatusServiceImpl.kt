package top.foxball.shopmall.service.impl

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminApplicationStatus
import top.foxball.shopmall.service.AdminDatabaseStatus
import top.foxball.shopmall.service.AdminJvmStatus
import top.foxball.shopmall.service.AdminRedisStatus
import top.foxball.shopmall.service.AdminSystemHealth
import top.foxball.shopmall.service.AdminSystemStatus
import top.foxball.shopmall.service.AdminSystemStatusService
import java.lang.management.ManagementFactory
import java.time.Instant
import javax.sql.DataSource
import kotlin.math.roundToLong

@Service
class AdminSystemStatusServiceImpl(
    private val adminAccessService: AdminAccessService,
    private val redisTemplate: StringRedisTemplate,
    private val dataSource: DataSource,
    private val meterRegistry: MeterRegistry,
    @Value($$"${spring.application.name:ShopMall}")
    private val applicationName: String,
) : AdminSystemStatusService {
    override fun getStatus(adminId: Long): AdminSystemStatus {
        adminAccessService.requireAdmin(adminId)

        val runtime = ManagementFactory.getRuntimeMXBean()
        val memory = ManagementFactory.getMemoryMXBean()
        val threads = ManagementFactory.getThreadMXBean()
        val operatingSystem = ManagementFactory.getOperatingSystemMXBean()
        val extendedOperatingSystem = operatingSystem as? com.sun.management.OperatingSystemMXBean
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()

        val databaseStartedAt = System.nanoTime()
        val databaseAvailable = runCatching {
            dataSource.connection.use { connection -> connection.isValid(2) }
        }.getOrDefault(false)
        val databaseLatencyMillis = ((System.nanoTime() - databaseStartedAt) / 1_000_000.0).roundToLong()

        val redisStartedAt = System.nanoTime()
        var redisAvailable = false
        var redisKeyCount: Long? = null
        var redisUsedMemoryBytes: Long? = null
        var redisConnectedClients: Long? = null
        var redisVersion: String? = null
        runCatching {
            val connectionFactory = requireNotNull(redisTemplate.connectionFactory)
            connectionFactory.connection.use { connection ->
                val commands = connection.serverCommands()
                val info = commands.info()
                redisKeyCount = commands.dbSize()
                redisUsedMemoryBytes = info.getProperty("used_memory")?.toLongOrNull()
                redisConnectedClients = info.getProperty("connected_clients")?.toLongOrNull()
                redisVersion = info.getProperty("redis_version")
                redisAvailable = true
            }
        }
        val redisLatencyMillis = ((System.nanoTime() - redisStartedAt) / 1_000_000.0).roundToLong()

        return AdminSystemStatus(
            status = if (databaseAvailable && redisAvailable) AdminSystemHealth.UP else AdminSystemHealth.DEGRADED,
            generatedAt = Instant.now(),
            application = AdminApplicationStatus(
                name = applicationName,
                version = javaClass.`package`?.implementationVersion ?: "development",
                startedAt = Instant.ofEpochMilli(runtime.startTime),
                uptimeSeconds = runtime.uptime / 1_000,
                availableProcessors = operatingSystem.availableProcessors,
                systemLoadAverage = operatingSystem.systemLoadAverage.takeIf { it.isFinite() && it >= 0 },
                processCpuUsage = extendedOperatingSystem?.processCpuLoad?.takeIf { it.isFinite() && it >= 0 },
                systemCpuUsage = extendedOperatingSystem?.cpuLoad?.takeIf { it.isFinite() && it >= 0 },
            ),
            jvm = AdminJvmStatus(
                heapUsedBytes = heap.used,
                heapCommittedBytes = heap.committed,
                heapMaxBytes = heap.max,
                nonHeapUsedBytes = nonHeap.used,
                liveThreads = threads.threadCount,
                peakThreads = threads.peakThreadCount,
                daemonThreads = threads.daemonThreadCount,
                gcCollectionCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) },
                gcCollectionTimeMillis = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) },
            ),
            database = AdminDatabaseStatus(
                available = databaseAvailable,
                latencyMillis = databaseLatencyMillis,
                activeConnections = gaugeValue("jdbc.connections.active"),
                idleConnections = gaugeValue("jdbc.connections.idle"),
                maxConnections = gaugeValue("jdbc.connections.max"),
            ),
            redis = AdminRedisStatus(
                available = redisAvailable,
                latencyMillis = redisLatencyMillis,
                keyCount = redisKeyCount,
                usedMemoryBytes = redisUsedMemoryBytes,
                connectedClients = redisConnectedClients,
                version = redisVersion,
            ),
        )
    }

    private fun gaugeValue(name: String): Long? {
        val gauges = meterRegistry.find(name).gauges()
        if (gauges.isEmpty()) return null
        val value = gauges.sumOf { it.value() }
        return value.takeIf { it.isFinite() && it >= 0 }?.roundToLong()
    }
}
