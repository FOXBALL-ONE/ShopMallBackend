package top.foxball.shopmall.logging

import ch.qos.logback.classic.Level
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

enum class LogLevel(val severity: Int) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    OFF(5),
    ;

    fun toLogbackLevel(): Level = when (this) {
        TRACE -> Level.TRACE
        DEBUG -> Level.DEBUG
        INFO -> Level.INFO
        WARN -> Level.WARN
        ERROR -> Level.ERROR
        OFF -> Level.OFF
    }

    companion object {
        fun parse(raw: String): LogLevel = entries.firstOrNull { it.name == raw.trim().uppercase(Locale.ROOT) }
            ?: throw IllegalArgumentException("Unknown log level: $raw")

        fun fromLogback(level: Level): LogLevel = when {
            level.isGreaterOrEqual(Level.ERROR) -> ERROR
            level.isGreaterOrEqual(Level.WARN) -> WARN
            level.isGreaterOrEqual(Level.INFO) -> INFO
            level.isGreaterOrEqual(Level.DEBUG) -> DEBUG
            else -> TRACE
        }
    }
}

enum class LoggingSettingsSource {
    DEFAULT,
    REDIS,
}

enum class RuntimeLoggingStatus {
    UP,
    DEGRADED,
}

data class LoggingSettings(
    val rootLevel: LogLevel,
    val loggerLevels: Map<String, LogLevel>,
    val outputTemplate: String,
    val version: Long,
    val source: LoggingSettingsSource,
    val updatedAt: LocalDateTime?,
    val updatedBy: Long?,
    internal val settingsId: String,
)

data class UpdateLoggingSettingsCommand(
    val rootLevel: String,
    val loggerOverrides: List<String>,
    val outputTemplate: String,
    val expectedVersion: Long,
)

sealed interface LoggingSettingsUpdateResult {
    data class Updated(val settings: LoggingSettings) : LoggingSettingsUpdateResult

    data class Conflict(val actualVersion: Long) : LoggingSettingsUpdateResult
}

data class LogRecord(
    val timestamp: LocalDateTime,
    val level: LogLevel,
    val thread: String,
    val logger: String,
    val requestId: String?,
    val message: String,
    val exception: String,
)

data class RenderedLogRecord(
    val text: String,
    val bytes: ByteArray,
    val truncated: Boolean,
)

data class FileLogRecord(
    val bytes: ByteArray,
    val truncated: Boolean,
    val occurredAt: LocalDateTime,
    val templateKey: String,
    val level: LogLevel,
)

data class FileBatchWriteResult(
    val success: Boolean,
    val writtenEvents: Int,
    val failedEvents: Int,
)

interface LogFileSink : AutoCloseable {
    /** Runs on the dedicated file worker so storage initialization cannot delay application startup. */
    fun initialize() = Unit

    fun writeBatch(records: List<FileLogRecord>): FileBatchWriteResult
}

data class LiveLogEvent(
    val sequence: Long,
    val timestamp: LocalDateTime,
    val level: LogLevel,
    val logger: String,
    val thread: String,
    val requestId: String?,
    val rendered: String,
    val templateVersion: Long,
    internal val retainedBytes: Long,
)

data class LiveLogQuery(
    val bootId: String?,
    val afterSequence: Long?,
    val minimumLevel: LogLevel,
    val loggerPrefix: String?,
    val query: String?,
    val limit: Int,
    val waitSeconds: Int,
)

data class LiveLogBatch(
    val bootId: String,
    val reset: Boolean,
    val gap: Boolean,
    val droppedCount: Long,
    val earliestSequence: Long,
    val nextSequence: Long,
    val events: List<LiveLogEvent>,
)

data class ActiveLogFile(
    val relativePath: String?,
    val sizeBytes: Long,
    val fileTime: LocalDateTime?,
    val rotationIndex: Int?,
    val lastWriteAt: LocalDateTime?,
    val lastError: String?,
)

data class RuntimeLoggingSnapshot(
    val effectiveVersion: Long,
    val status: RuntimeLoggingStatus,
    val activeFile: ActiveLogFile,
    val lastError: String?,
)

data class LoggingTemplatePreview(
    val rendered: String,
    val encodedSizeBytes: Int,
)

data class LogDateSummary(
    val date: LocalDate,
    val fileCount: Int,
    val sizeBytes: Long,
)

data class HistoricalLogFile(
    val date: LocalDate,
    val fileTime: LocalDateTime,
    val rotationIndex: Int,
    val filename: String,
    val sizeBytes: Long,
    val modifiedAt: LocalDateTime,
    val active: Boolean,
)

data class HistoryFilePage(
    val files: List<HistoricalLogFile>,
    val nextCursor: Int?,
)

data class HistoryLogLine(
    val offset: Long,
    val nextOffset: Long,
    val text: String,
)

data class HistoryContentPage(
    val filename: String,
    val fileSizeBytes: Long,
    val active: Boolean,
    val lines: List<HistoryLogLine>,
    val nextOffset: Long,
    val eof: Boolean,
)

class LoggingUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LiveLogPollLimitException(
    val retryAfterSeconds: Long = 1,
    message: String = "Too many concurrent live log requests",
) : RuntimeException(message)
