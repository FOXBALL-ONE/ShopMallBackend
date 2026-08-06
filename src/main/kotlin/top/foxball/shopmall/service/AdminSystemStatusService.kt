package top.foxball.shopmall.service

import java.time.Instant

enum class AdminSystemHealth {
    UP,
    DEGRADED,
}

data class AdminApplicationStatus(
    val name: String,
    val version: String,
    val startedAt: Instant,
    val uptimeSeconds: Long,
    val availableProcessors: Int,
    val systemLoadAverage: Double?,
    val processCpuUsage: Double?,
    val systemCpuUsage: Double?,
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

data class AdminDatabaseStatus(
    val available: Boolean,
    val latencyMillis: Long,
    val activeConnections: Long?,
    val idleConnections: Long?,
    val maxConnections: Long?,
)

data class AdminRedisStatus(
    val available: Boolean,
    val latencyMillis: Long,
    val keyCount: Long?,
    val usedMemoryBytes: Long?,
    val connectedClients: Long?,
    val version: String?,
)

data class AdminSystemStatus(
    val status: AdminSystemHealth,
    val generatedAt: Instant,
    val application: AdminApplicationStatus,
    val jvm: AdminJvmStatus,
    val database: AdminDatabaseStatus,
    val redis: AdminRedisStatus,
)

interface AdminSystemStatusService {
    fun getStatus(adminId: Long): AdminSystemStatus
}
