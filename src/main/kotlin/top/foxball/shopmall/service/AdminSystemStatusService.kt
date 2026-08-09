package top.foxball.shopmall.service

import java.time.LocalDateTime

data class AdminApplicationStatus(
    val name: String,
    val version: String,
    val startedAt: LocalDateTime?,
    val uptimeSeconds: Long,
)

data class AdminSystemResourcesStatus(
    val availableProcessors: Int,
    val systemLoadAverage: Double?,
    val processCpuUsage: Double?,
    val systemCpuUsage: Double?,
    val diskTotalBytes: Long?,
    val diskFreeBytes: Long?,
)

data class AdminJvmStatus(
    val heapUsedBytes: Long,
    val heapCommittedBytes: Long,
    val heapMaxBytes: Long,
    val nonHeapUsedBytes: Long,
    val liveThreads: Int,
    val peakThreads: Int,
    val daemonThreads: Int,
    val gcCollectionCount: Long,
    val gcCollectionTimeMillis: Long,
)

data class AdminHttpStatus(
    val requestCount: Long,
    val activeRequests: Long,
    val serverErrorCount: Long,
    val averageDurationMillis: Double?,
    val maxDurationMillis: Double?,
)

data class AdminDatabaseStatus(
    val status: String,
    val activeConnections: Long?,
    val idleConnections: Long?,
    val minConnections: Long?,
    val maxConnections: Long?,
)

data class AdminRedisStatus(
    val status: String,
    val version: String? = null,
    val mode: String? = null,
    val role: String? = null,
    val database: Int? = null,
    val keyCount: Long? = null,
    val expiringKeyCount: Long? = null,
    val averageTtlMillis: Long? = null,
    val uptimeSeconds: Long? = null,
    val usedMemoryBytes: Long? = null,
    val peakMemoryBytes: Long? = null,
    val maxMemoryBytes: Long? = null,
    val connectedClients: Long? = null,
    val maxClients: Long? = null,
    val blockedClients: Long? = null,
    val totalCommandsProcessed: Long? = null,
    val operationsPerSecond: Long? = null,
    val keyspaceHits: Long? = null,
    val keyspaceMisses: Long? = null,
    val evictedKeys: Long? = null,
)

data class AdminHealthComponentStatus(
    val id: String,
    val status: String,
    val details: Map<String, String> = emptyMap(),
)

data class AdminSystemStatus(
    val status: String,
    val generatedAt: LocalDateTime,
    val collectionDurationMillis: Long,
    val application: AdminApplicationStatus,
    val system: AdminSystemResourcesStatus,
    val jvm: AdminJvmStatus,
    val http: AdminHttpStatus,
    val database: AdminDatabaseStatus,
    val redis: AdminRedisStatus,
    val healthComponents: List<AdminHealthComponentStatus>,
)

interface AdminSystemStatusService {
    fun getStatus(adminId: Long): AdminSystemStatus
}
