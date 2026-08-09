package top.foxball.shopmall.logging

import org.springframework.stereotype.Service
import top.foxball.shopmall.service.AdminAccessService

/** Admin-authorized facade for the local live tail buffer. */
@Service
class LiveLogService(
    private val adminAccessService: AdminAccessService,
    private val liveLogBuffer: LiveLogBuffer,
    private val liveLogPollLimiter: LiveLogPollLimiter,
    private val properties: LoggingProperties,
) {
    suspend fun poll(adminId: Long, query: LiveLogQuery): LiveLogBatch {
        adminAccessService.requireAdmin(adminId)
        require(query.minimumLevel != LogLevel.OFF) { "minimum_level must not be OFF for live polling" }
        require(query.bootId == null || query.bootId.length <= MAX_BOOT_ID_LENGTH) {
            "boot_id must not exceed $MAX_BOOT_ID_LENGTH characters"
        }
        require(query.afterSequence == null || query.afterSequence >= 0) { "after_sequence must not be negative" }
        require(query.loggerPrefix == null || query.loggerPrefix.length <= MAX_LOGGER_PREFIX_LENGTH) {
            "logger_prefix must not exceed $MAX_LOGGER_PREFIX_LENGTH characters"
        }
        require(query.query == null || query.query.length <= LogHistoryService.MAX_QUERY_LENGTH) {
            "query must not exceed ${LogHistoryService.MAX_QUERY_LENGTH} characters"
        }
        require(query.limit in 1..500) { "limit must be between 1 and 500" }
        require(query.waitSeconds in 0..properties.liveMaxWaitSeconds) {
            "wait_seconds must be between 0 and ${properties.liveMaxWaitSeconds}"
        }
        return liveLogPollLimiter.withPermit(adminId) { liveLogBuffer.poll(query) }
    }

    private companion object {
        const val MAX_BOOT_ID_LENGTH = 64
        const val MAX_LOGGER_PREFIX_LENGTH = 200
    }
}
