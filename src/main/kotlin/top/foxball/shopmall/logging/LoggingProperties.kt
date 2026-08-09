package top.foxball.shopmall.logging

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import java.time.ZoneId

/** Deployment-level logging options. Runtime administrators cannot change these values. */
@ConfigurationProperties(prefix = "shopmall.logging")
data class LoggingProperties(
    val storagePath: String = "./logs",
    val timeZone: String = "Asia/Shanghai",
    val instanceId: String = "local",
    val maxFileSize: DataSize = DataSize.ofMegabytes(10),
    val maxRecordSize: DataSize = DataSize.ofKilobytes(256),
    val retentionDays: Int = 30,
    val liveBufferEvents: Int = 5_000,
    val liveBufferBytes: Long = 16L * 1024 * 1024,
    val liveResponseBytes: Long = 2L * 1024 * 1024,
    val liveBatchWindowMillis: Long = 100,
    val liveMaxWaitSeconds: Int = 20,
    val liveMaxNodePolls: Int = 50,
    val liveMaxAdminPolls: Int = 2,
    val asyncQueueEvents: Int = 4_096,
    val asyncQueueBytes: Long = 32L * 1024 * 1024,
    val fileQueueEvents: Int = 4_096,
    val fileQueueBytes: Long = 32L * 1024 * 1024,
    val fileBatchEvents: Int = 128,
    val fileFlushIntervalMillis: Long = 100,
    val fileFailureBackoffMillis: Long = 1_000,
    val shutdownTimeoutMillis: Long = 5_000,
    val reconcileIntervalMillis: Long = 5_000,
    val failOnFileError: Boolean = true,
    val defaultRootLevel: String = "INFO",
    val defaultOutputTemplate: String = DEFAULT_OUTPUT_TEMPLATE,
) {
    val rootPath: Path = Path.of(storagePath).toAbsolutePath().normalize()
    val zoneId: ZoneId = ZoneId.of(timeZone)

    init {
        require(storagePath.isNotBlank()) { "shopmall.logging.storage-path must not be blank" }
        require(instanceId.isNotBlank() && instanceId.length <= 100) {
            "shopmall.logging.instance-id must contain between 1 and 100 characters"
        }
        require(maxFileSize.toBytes() == MAX_FILE_SIZE_BYTES) {
            "shopmall.logging.max-file-size must be exactly 10 MiB"
        }
        require(maxRecordSize.toBytes() in 1 until maxFileSize.toBytes()) {
            "shopmall.logging.max-record-size must be positive and smaller than max-file-size"
        }
        val minimumAsyncQueueBytes = minimumAsyncQueueBytes(maxRecordSize.toBytes())
        val minimumFileQueueBytes = minimumFileQueueBytes(maxRecordSize.toBytes())
        require(retentionDays in 1..3_650) { "shopmall.logging.retention-days must be between 1 and 3650" }
        require(liveBufferEvents in 100..100_000) {
            "shopmall.logging.live-buffer-events must be between 100 and 100000"
        }
        require(liveBufferBytes >= maxRecordSize.toBytes() && liveBufferBytes <= 512L * 1024 * 1024) {
            "shopmall.logging.live-buffer-bytes must fit one record and must not exceed 512 MiB"
        }
        require(liveResponseBytes >= maxRecordSize.toBytes() && liveResponseBytes <= 64L * 1024 * 1024) {
            "shopmall.logging.live-response-bytes must fit one record and must not exceed 64 MiB"
        }
        require(liveBatchWindowMillis in 0..1_000) {
            "shopmall.logging.live-batch-window-millis must be between 0 and 1000"
        }
        require(liveMaxWaitSeconds in 1..20) {
            "shopmall.logging.live-max-wait-seconds must be between 1 and 20"
        }
        require(liveMaxNodePolls in 1..1_000) {
            "shopmall.logging.live-max-node-polls must be between 1 and 1000"
        }
        require(liveMaxAdminPolls in 1..20) {
            "shopmall.logging.live-max-admin-polls must be between 1 and 20"
        }
        require(asyncQueueEvents in 1..100_000) {
            "shopmall.logging.async-queue-events must be between 1 and 100000"
        }
        require(asyncQueueBytes >= minimumAsyncQueueBytes && asyncQueueBytes <= 1024L * 1024 * 1024) {
            "shopmall.logging.async-queue-bytes must be at least $minimumAsyncQueueBytes bytes " +
                "to fit one maximum plain captured record and must not exceed 1 GiB"
        }
        require(fileQueueEvents in 1..100_000) {
            "shopmall.logging.file-queue-events must be between 1 and 100000"
        }
        require(fileQueueBytes >= minimumFileQueueBytes && fileQueueBytes <= 1024L * 1024 * 1024) {
            "shopmall.logging.file-queue-bytes must be at least $minimumFileQueueBytes bytes " +
                "to fit one maximum rendered record and must not exceed 1 GiB"
        }
        require(fileBatchEvents in 1..fileQueueEvents) {
            "shopmall.logging.file-batch-events must be between 1 and file-queue-events"
        }
        require(fileFlushIntervalMillis in 10..5_000) {
            "shopmall.logging.file-flush-interval-millis must be between 10 and 5000"
        }
        require(fileFailureBackoffMillis in 10..60_000) {
            "shopmall.logging.file-failure-backoff-millis must be between 10 and 60000"
        }
        require(shutdownTimeoutMillis in 100..30_000) {
            "shopmall.logging.shutdown-timeout-millis must be between 100 and 30000"
        }
        require(reconcileIntervalMillis >= 1_000) {
            "shopmall.logging.reconcile-interval-millis must be at least 1000"
        }
        LogLevel.parse(defaultRootLevel)
        RuntimeLogFormatter.compile(defaultOutputTemplate)
    }

    companion object {
        const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024
        const val DEFAULT_OUTPUT_TEMPLATE =
            "{timestamp} [{level}] [{thread}] {logger} request_id={request_id} - {message}{exception}"

        internal const val ASYNC_EVENT_OVERHEAD_BYTES = 256L
        internal const val FILE_EVENT_OVERHEAD_BYTES = 128L
        internal const val ESTIMATED_CHARACTER_BYTES = 2L
        internal const val MAX_LOGGER_CHARACTERS = 1_024
        internal const val MAX_THREAD_CHARACTERS = 256
        internal const val MAX_REQUEST_ID_CHARACTERS = 512

        internal fun minimumAsyncQueueBytes(maxRecordBytes: Long): Long =
            ASYNC_EVENT_OVERHEAD_BYTES +
                maxRecordBytes * ESTIMATED_CHARACTER_BYTES +
                (MAX_LOGGER_CHARACTERS + MAX_THREAD_CHARACTERS + MAX_REQUEST_ID_CHARACTERS) *
                ESTIMATED_CHARACTER_BYTES

        internal fun minimumFileQueueBytes(maxRecordBytes: Long): Long =
            maxRecordBytes + FILE_EVENT_OVERHEAD_BYTES
    }
}
