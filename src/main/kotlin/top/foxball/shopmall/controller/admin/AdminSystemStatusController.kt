package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.service.AdminSystemStatusService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/**
 * @folder 管理端/系统监控
 */
@RestController
@RequestMapping("/admin/api/system-status")
class AdminSystemStatusController(
    private val systemStatusService: AdminSystemStatusService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取 Actuator 系统运行状态
     */
    @GetMapping
    fun getSystemStatus(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class ApplicationData(
            val name: String,
            val version: String,
            @param:JsonProperty("started_at") val startedAt: LocalDateTime?,
            @param:JsonProperty("uptime_seconds") val uptimeSeconds: Long,
        )

        data class SystemData(
            @param:JsonProperty("available_processors") val availableProcessors: Int,
            @param:JsonProperty("system_load_average") val systemLoadAverage: Double?,
            @param:JsonProperty("process_cpu_usage") val processCpuUsage: Double?,
            @param:JsonProperty("system_cpu_usage") val systemCpuUsage: Double?,
            @param:JsonProperty("disk_total_bytes") val diskTotalBytes: Long?,
            @param:JsonProperty("disk_free_bytes") val diskFreeBytes: Long?,
        )

        data class JvmData(
            @param:JsonProperty("heap_used_bytes") val heapUsedBytes: Long,
            @param:JsonProperty("heap_committed_bytes") val heapCommittedBytes: Long,
            @param:JsonProperty("heap_max_bytes") val heapMaxBytes: Long,
            @param:JsonProperty("non_heap_used_bytes") val nonHeapUsedBytes: Long,
            @param:JsonProperty("live_threads") val liveThreads: Int,
            @param:JsonProperty("peak_threads") val peakThreads: Int,
            @param:JsonProperty("daemon_threads") val daemonThreads: Int,
            @param:JsonProperty("gc_collection_count") val gcCollectionCount: Long,
            @param:JsonProperty("gc_collection_time_ms") val gcCollectionTimeMillis: Long,
        )

        data class HttpData(
            @param:JsonProperty("request_count") val requestCount: Long,
            @param:JsonProperty("active_requests") val activeRequests: Long,
            @param:JsonProperty("server_error_count") val serverErrorCount: Long,
            @param:JsonProperty("average_duration_ms") val averageDurationMillis: Double?,
            @param:JsonProperty("max_duration_ms") val maxDurationMillis: Double?,
        )

        data class DatabaseData(
            val status: String,
            @param:JsonProperty("active_connections") val activeConnections: Long?,
            @param:JsonProperty("idle_connections") val idleConnections: Long?,
            @param:JsonProperty("min_connections") val minConnections: Long?,
            @param:JsonProperty("max_connections") val maxConnections: Long?,
        )

        data class RedisData(
            val status: String,
            val version: String?,
            val mode: String?,
            val role: String?,
            val database: Int?,
            @param:JsonProperty("key_count") val keyCount: Long?,
            @param:JsonProperty("expiring_key_count") val expiringKeyCount: Long?,
            @param:JsonProperty("average_ttl_ms") val averageTtlMillis: Long?,
            @param:JsonProperty("uptime_seconds") val uptimeSeconds: Long?,
            @param:JsonProperty("used_memory_bytes") val usedMemoryBytes: Long?,
            @param:JsonProperty("peak_memory_bytes") val peakMemoryBytes: Long?,
            @param:JsonProperty("max_memory_bytes") val maxMemoryBytes: Long?,
            @param:JsonProperty("connected_clients") val connectedClients: Long?,
            @param:JsonProperty("max_clients") val maxClients: Long?,
            @param:JsonProperty("blocked_clients") val blockedClients: Long?,
            @param:JsonProperty("total_commands_processed") val totalCommandsProcessed: Long?,
            @param:JsonProperty("operations_per_second") val operationsPerSecond: Long?,
            @param:JsonProperty("keyspace_hits") val keyspaceHits: Long?,
            @param:JsonProperty("keyspace_misses") val keyspaceMisses: Long?,
            @param:JsonProperty("evicted_keys") val evictedKeys: Long?,
        )

        data class HealthComponentData(
            val id: String,
            val status: String,
            val details: Map<String, String>,
        )

        data class Response(
            val status: String,
            val source: String,
            @param:JsonProperty("generated_at") val generatedAt: LocalDateTime,
            @param:JsonProperty("collection_duration_ms") val collectionDurationMillis: Long,
            val application: ApplicationData,
            val system: SystemData,
            val jvm: JvmData,
            val http: HttpData,
            val database: DatabaseData,
            val redis: RedisData,
            @param:JsonProperty("health_components") val healthComponents: List<HealthComponentData>,
        )

        val status = systemStatusService.getStatus(adminId)
        val rs = Response(
            status = status.status,
            source = "Spring Boot Actuator",
            generatedAt = status.generatedAt,
            collectionDurationMillis = status.collectionDurationMillis,
            application = ApplicationData(
                name = status.application.name,
                version = status.application.version,
                startedAt = status.application.startedAt,
                uptimeSeconds = status.application.uptimeSeconds,
            ),
            system = SystemData(
                availableProcessors = status.system.availableProcessors,
                systemLoadAverage = status.system.systemLoadAverage,
                processCpuUsage = status.system.processCpuUsage,
                systemCpuUsage = status.system.systemCpuUsage,
                diskTotalBytes = status.system.diskTotalBytes,
                diskFreeBytes = status.system.diskFreeBytes,
            ),
            jvm = JvmData(
                heapUsedBytes = status.jvm.heapUsedBytes,
                heapCommittedBytes = status.jvm.heapCommittedBytes,
                heapMaxBytes = status.jvm.heapMaxBytes,
                nonHeapUsedBytes = status.jvm.nonHeapUsedBytes,
                liveThreads = status.jvm.liveThreads,
                peakThreads = status.jvm.peakThreads,
                daemonThreads = status.jvm.daemonThreads,
                gcCollectionCount = status.jvm.gcCollectionCount,
                gcCollectionTimeMillis = status.jvm.gcCollectionTimeMillis,
            ),
            http = HttpData(
                requestCount = status.http.requestCount,
                activeRequests = status.http.activeRequests,
                serverErrorCount = status.http.serverErrorCount,
                averageDurationMillis = status.http.averageDurationMillis,
                maxDurationMillis = status.http.maxDurationMillis,
            ),
            database = DatabaseData(
                status = status.database.status,
                activeConnections = status.database.activeConnections,
                idleConnections = status.database.idleConnections,
                minConnections = status.database.minConnections,
                maxConnections = status.database.maxConnections,
            ),
            redis = RedisData(
                status = status.redis.status,
                version = status.redis.version,
                mode = status.redis.mode,
                role = status.redis.role,
                database = status.redis.database,
                keyCount = status.redis.keyCount,
                expiringKeyCount = status.redis.expiringKeyCount,
                averageTtlMillis = status.redis.averageTtlMillis,
                uptimeSeconds = status.redis.uptimeSeconds,
                usedMemoryBytes = status.redis.usedMemoryBytes,
                peakMemoryBytes = status.redis.peakMemoryBytes,
                maxMemoryBytes = status.redis.maxMemoryBytes,
                connectedClients = status.redis.connectedClients,
                maxClients = status.redis.maxClients,
                blockedClients = status.redis.blockedClients,
                totalCommandsProcessed = status.redis.totalCommandsProcessed,
                operationsPerSecond = status.redis.operationsPerSecond,
                keyspaceHits = status.redis.keyspaceHits,
                keyspaceMisses = status.redis.keyspaceMisses,
                evictedKeys = status.redis.evictedKeys,
            ),
            healthComponents = status.healthComponents.map { component ->
                HealthComponentData(
                    id = component.id,
                    status = component.status,
                    details = component.details,
                )
            },
        )
        return builder.ok().data(rs).build()
    }
}
