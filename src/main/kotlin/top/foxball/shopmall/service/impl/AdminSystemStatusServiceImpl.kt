package top.foxball.shopmall.service.impl

import io.micrometer.core.instrument.Statistic
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint
import org.springframework.stereotype.Service
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminApplicationStatus
import top.foxball.shopmall.service.AdminDatabaseStatus
import top.foxball.shopmall.service.AdminHealthComponentStatus
import top.foxball.shopmall.service.AdminHttpStatus
import top.foxball.shopmall.service.AdminJvmStatus
import top.foxball.shopmall.service.AdminRedisStatus
import top.foxball.shopmall.service.AdminSystemResourcesStatus
import top.foxball.shopmall.service.AdminSystemStatus
import top.foxball.shopmall.service.AdminSystemStatusService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Service
class AdminSystemStatusServiceImpl(
    private val adminAccessService: AdminAccessService,
    private val healthEndpoint: HealthEndpoint,
    meterRegistry: MeterRegistry,
    @Value($$"${spring.application.name:ShopMall}")
    private val applicationName: String,
) : AdminSystemStatusService {
    private val metricsEndpoint = MetricsEndpoint(meterRegistry)

    override fun getStatus(adminId: Long): AdminSystemStatus {
        adminAccessService.requireAdmin(adminId)
        val collectionStartedAt = System.nanoTime()

        val health = healthEndpoint.health()
        val healthComponents = mutableListOf<AdminHealthComponentStatus>()
        if (health is CompositeHealthDescriptor) {
            health.components.orEmpty().forEach { (id, component) ->
                collectHealthComponents(id, component, healthComponents)
            }
        }

        val processStartTime = metricValue("process.start.time")
            ?.takeIf { it > 0 }
            ?.let { epochSeconds ->
                Instant.ofEpochMilli((epochSeconds * 1_000).roundToLong())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
            }
        val uptimeSeconds = nonNegativeLong(metricValue("process.uptime")) ?: 0

        val httpMetric = metricDescriptor("http.server.requests")
        val requestCount = nonNegativeLong(sample(httpMetric, Statistic.COUNT)) ?: 0
        val totalRequestTimeSeconds = sample(httpMetric, Statistic.TOTAL_TIME)
        val statusTag = httpMetric?.availableTags?.firstOrNull { it.tag == "status" }
        val serverErrorCount = statusTag?.values
            .orEmpty()
            .asSequence()
            .filter { status -> status.length == 3 && status.startsWith('5') }
            .sumOf { status -> metricValue("http.server.requests", Statistic.COUNT, listOf("status:$status")) ?: 0.0 }
            .roundToLong()

        val databaseStatus = healthComponents
            .firstOrNull { it.id == "db" || it.id.startsWith("db.") }
            ?.status
            ?: "UNKNOWN"
        val redisComponent = healthComponents
            .firstOrNull { it.id == "redis" || it.id.startsWith("redis.") }
        val redisStatus = redisComponent?.status ?: "UNKNOWN"
        val redisDetails = healthComponents
            .firstOrNull { (it.id == "redis" || it.id.startsWith("redis.")) && it.details.isNotEmpty() }
            ?.details
            ?: emptyMap()

        val application = AdminApplicationStatus(
            name = applicationName,
            version = javaClass.`package`?.implementationVersion ?: "development",
            startedAt = processStartTime,
            uptimeSeconds = uptimeSeconds,
        )
        val system = AdminSystemResourcesStatus(
            availableProcessors = nonNegativeInt(metricValue("system.cpu.count")) ?: 0,
            systemLoadAverage = nonNegativeDouble(metricValue("system.load.average.1m")),
            processCpuUsage = usageRatio(metricValue("process.cpu.usage")),
            systemCpuUsage = usageRatio(metricValue("system.cpu.usage")),
            diskTotalBytes = nonNegativeLong(metricValue("disk.total")),
            diskFreeBytes = nonNegativeLong(metricValue("disk.free")),
        )
        val jvm = AdminJvmStatus(
            heapUsedBytes = nonNegativeLong(metricValue("jvm.memory.used", tags = listOf("area:heap"))) ?: 0,
            heapCommittedBytes = nonNegativeLong(metricValue("jvm.memory.committed", tags = listOf("area:heap"))) ?: 0,
            heapMaxBytes = nonNegativeLong(metricValue("jvm.memory.max", tags = listOf("area:heap"))) ?: 0,
            nonHeapUsedBytes = nonNegativeLong(metricValue("jvm.memory.used", tags = listOf("area:nonheap"))) ?: 0,
            liveThreads = nonNegativeInt(metricValue("jvm.threads.live")) ?: 0,
            peakThreads = nonNegativeInt(metricValue("jvm.threads.peak")) ?: 0,
            daemonThreads = nonNegativeInt(metricValue("jvm.threads.daemon")) ?: 0,
            gcCollectionCount = nonNegativeLong(metricValue("jvm.gc.pause", Statistic.COUNT)) ?: 0,
            gcCollectionTimeMillis = nonNegativeLong(
                metricValue("jvm.gc.pause", Statistic.TOTAL_TIME)?.times(1_000),
            ) ?: 0,
        )
        val http = AdminHttpStatus(
            requestCount = requestCount,
            activeRequests = nonNegativeLong(
                metricValue("http.server.requests.active", Statistic.ACTIVE_TASKS)
                    ?: metricValue("http.server.requests.active"),
            ) ?: 0,
            serverErrorCount = serverErrorCount,
            averageDurationMillis = if (requestCount > 0 && totalRequestTimeSeconds != null) {
                totalRequestTimeSeconds * 1_000 / requestCount
            } else {
                null
            },
            maxDurationMillis = nonNegativeDouble(sample(httpMetric, Statistic.MAX))?.times(1_000),
        )
        val database = AdminDatabaseStatus(
            status = databaseStatus,
            activeConnections = nonNegativeLong(metricValue("jdbc.connections.active")),
            idleConnections = nonNegativeLong(metricValue("jdbc.connections.idle")),
            minConnections = nonNegativeLong(metricValue("jdbc.connections.min")),
            maxConnections = nonNegativeLong(metricValue("jdbc.connections.max")),
        )

        return AdminSystemStatus(
            status = normalizedStatus(health.status.code),
            generatedAt = LocalDateTime.now(),
            collectionDurationMillis = ((System.nanoTime() - collectionStartedAt) / 1_000_000.0).roundToLong(),
            application = application,
            system = system,
            jvm = jvm,
            http = http,
            database = database,
            redis = AdminRedisStatus(
                status = redisStatus,
                version = redisDetails["version"],
                mode = redisDetails["mode"],
                role = redisDetails["role"],
                database = redisDetails.nonNegativeInt("database"),
                keyCount = redisDetails.nonNegativeLong("key_count"),
                expiringKeyCount = redisDetails.nonNegativeLong("expiring_key_count"),
                averageTtlMillis = redisDetails.nonNegativeLong("average_ttl_ms"),
                uptimeSeconds = redisDetails.nonNegativeLong("uptime_seconds"),
                usedMemoryBytes = redisDetails.nonNegativeLong("used_memory_bytes"),
                peakMemoryBytes = redisDetails.nonNegativeLong("peak_memory_bytes"),
                maxMemoryBytes = redisDetails.nonNegativeLong("max_memory_bytes"),
                connectedClients = redisDetails.nonNegativeLong("connected_clients"),
                maxClients = redisDetails.nonNegativeLong("max_clients"),
                blockedClients = redisDetails.nonNegativeLong("blocked_clients"),
                totalCommandsProcessed = redisDetails.nonNegativeLong("total_commands_processed"),
                operationsPerSecond = redisDetails.nonNegativeLong("operations_per_second"),
                keyspaceHits = redisDetails.nonNegativeLong("keyspace_hits"),
                keyspaceMisses = redisDetails.nonNegativeLong("keyspace_misses"),
                evictedKeys = redisDetails.nonNegativeLong("evicted_keys"),
            ),
            healthComponents = healthComponents.sortedBy { it.id },
        )
    }

    private fun collectHealthComponents(
        id: String,
        descriptor: HealthDescriptor,
        target: MutableList<AdminHealthComponentStatus>,
    ) {
        val details = if (descriptor is IndicatedHealthDescriptor) {
            descriptor.details.orEmpty().mapValues { (_, value) -> stringifyHealthDetail(value) }
        } else {
            emptyMap()
        }
        target += AdminHealthComponentStatus(
            id = id,
            status = normalizedStatus(descriptor.status.code),
            details = details,
        )
        if (descriptor is CompositeHealthDescriptor) {
            descriptor.components.orEmpty().forEach { (childId, child) ->
                collectHealthComponents("$id.$childId", child, target)
            }
        }
    }

    private fun metricDescriptor(
        name: String,
        tags: List<String> = emptyList(),
    ): MetricsEndpoint.MetricDescriptor? = runCatching { metricsEndpoint.metric(name, tags) }.getOrNull()

    private fun metricValue(
        name: String,
        statistic: Statistic = Statistic.VALUE,
        tags: List<String> = emptyList(),
    ): Double? = sample(metricDescriptor(name, tags), statistic)

    private fun sample(
        descriptor: MetricsEndpoint.MetricDescriptor?,
        statistic: Statistic,
    ): Double? = descriptor
        ?.measurements
        ?.firstOrNull { it.statistic == statistic }
        ?.value
        ?.takeIf { it.isFinite() }

    private fun nonNegativeDouble(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }

    private fun usageRatio(value: Double?): Double? = nonNegativeDouble(value)?.coerceAtMost(1.0)

    private fun nonNegativeLong(value: Double?): Long? = nonNegativeDouble(value)?.roundToLong()

    private fun nonNegativeInt(value: Double?): Int? = nonNegativeDouble(value)?.roundToInt()

    private fun Map<String, String>.nonNegativeLong(name: String): Long? =
        this[name]?.toLongOrNull()?.takeIf { it >= 0 }

    private fun Map<String, String>.nonNegativeInt(name: String): Int? =
        this[name]?.toIntOrNull()?.takeIf { it >= 0 }

    private fun stringifyHealthDetail(value: Any?): String = when (value) {
        null -> "--"
        is Map<*, *> -> value.entries.joinToString(", ") { (key, item) -> "$key: ${stringifyHealthDetail(item)}" }
        is Iterable<*> -> value.joinToString(", ") { item -> stringifyHealthDetail(item) }
        is Array<*> -> value.joinToString(", ") { item -> stringifyHealthDetail(item) }
        else -> value.toString()
    }

    private fun normalizedStatus(status: String?): String = status?.trim()?.uppercase()?.ifEmpty { "UNKNOWN" } ?: "UNKNOWN"
}
