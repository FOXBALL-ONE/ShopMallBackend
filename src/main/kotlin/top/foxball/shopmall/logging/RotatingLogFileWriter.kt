package top.foxball.shopmall.logging

import ch.qos.logback.core.status.ErrorStatus
import ch.qos.logback.core.status.StatusManager
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns one local process' log file group. It never opens an existing file for append, so every
 * process start gets a fresh file even when the wall clock collides at millisecond precision.
 */
class RotatingLogFileWriter(
    private val properties: LoggingProperties,
    private val metrics: LoggingMetrics,
    private val statusManager: StatusManager?,
    private val clock: Clock = Clock.systemUTC(),
) : LogFileSink {
    private val lock = ReentrantLock()
    private var output: OutputStream? = null
    @Volatile
    private var activePath: Path? = null
    @Volatile
    private var groupTime: LocalDateTime? = null
    private var activeDate: LocalDate? = null
    @Volatile
    private var rotationIndex = 0
    @Volatile
    private var activeSize = 0L
    @Volatile
    private var lastWriteAt: LocalDateTime? = null
    @Volatile
    private var lastError: String? = null
    private var formatterKey: String? = null

    /** Creates this process' initial `-0.log` before application work starts. */
    override fun initialize() {
        lock.withLock {
            if (output != null) return
            try {
                openNewGroup(LocalDateTime.now(clock.withZone(properties.zoneId)), "startup")
            } catch (exception: Exception) {
                lastError = exception.message ?: exception.javaClass.simpleName
                metrics.writeFailed()
                statusManager?.add(ErrorStatus("ShopMall log file initialization failed: $lastError", this, exception))
                if (properties.failOnFileError) {
                    throw IllegalStateException("Unable to initialize ShopMall log file", exception)
                }
            }
        }
    }

    fun write(
        rendered: RenderedLogRecord,
        occurredAt: LocalDateTime,
        templateKey: String,
        level: LogLevel,
    ) {
        writeBatch(
            listOf(
                FileLogRecord(
                    bytes = rendered.bytes,
                    truncated = rendered.truncated,
                    occurredAt = occurredAt,
                    templateKey = templateKey,
                    level = level,
                ),
            ),
        )
    }

    /** Writes and flushes a bounded batch while holding the file-state lock only once. */
    override fun writeBatch(records: List<FileLogRecord>): FileBatchWriteResult {
        if (records.isEmpty()) return FileBatchWriteResult(success = true, writtenEvents = 0, failedEvents = 0)
        lock.withLock {
            var writtenEvents = 0
            try {
                records.forEach { record ->
                    val recordSize = record.bytes.size.toLong()
                    require(recordSize <= properties.maxFileSize.toBytes()) {
                        "A rendered record exceeds the configured maximum file size"
                    }
                    val date = record.occurredAt.toLocalDate()
                    when {
                        output == null -> openNewGroup(record.occurredAt, "recovery")
                        activeDate != date -> openNewGroup(record.occurredAt, "date")
                        formatterKey != null && formatterKey != record.templateKey -> rotate("format")
                        activeSize > 0 && activeSize + recordSize > properties.maxFileSize.toBytes() -> rotate("size")
                    }
                    output!!.write(record.bytes)
                    activeSize += recordSize
                    lastWriteAt = record.occurredAt
                    formatterKey = record.templateKey
                    metrics.activeFileSize(activeSize)
                    metrics.written(record.level, record.bytes.size, record.truncated)
                    writtenEvents++
                }
                output!!.flush()
                lastError = null
                return FileBatchWriteResult(success = true, writtenEvents = writtenEvents, failedEvents = 0)
            } catch (exception: Exception) {
                lastError = exception.message ?: exception.javaClass.simpleName
                metrics.writeFailed()
                statusManager?.add(ErrorStatus("ShopMall file logging failed: $lastError", this, exception))
                closeOutput()
                return FileBatchWriteResult(
                    success = false,
                    writtenEvents = writtenEvents,
                    failedEvents = records.size - writtenEvents,
                )
            }
        }
    }

    /** Diagnostic reads never wait behind a slow file write or flush. */
    fun activeFile(): ActiveLogFile = ActiveLogFile(
            relativePath = activePath?.let { properties.rootPath.relativize(it).toString().replace('\\', '/') },
            sizeBytes = activeSize,
            fileTime = groupTime,
            rotationIndex = activePath?.let { rotationIndex },
            lastWriteAt = lastWriteAt,
            lastError = lastError,
        )

    fun isActive(path: Path): Boolean =
        activePath?.toAbsolutePath()?.normalize() == path.toAbsolutePath().normalize()

    override fun close() {
        lock.withLock {
            runCatching { output?.flush() }
            runCatching { output?.close() }
            output = null
        }
    }

    private fun openNewGroup(occurredAt: LocalDateTime, reason: String) {
        closeOutput()
        val date = occurredAt.toLocalDate()
        val directory = properties.rootPath
            .resolve(date.format(DateTimeFormatter.ofPattern("yyyy")))
            .resolve(date.format(DateTimeFormatter.ofPattern("MM")))
            .resolve(date.format(DateTimeFormatter.ofPattern("dd")))
        ensureDirectory(directory)

        // The filename deliberately has millisecond precision. Truncating before the
        // CREATE_NEW loop keeps the diagnostic file time, path and collision increments
        // consistent even when a caller supplies a nanosecond-precision timestamp.
        var candidateTime = occurredAt.truncatedTo(ChronoUnit.MILLIS)
        while (true) {
            val filename = "${candidateTime.format(FILE_TIME_FORMAT)}-0.log"
            val candidate = directory.resolve(filename)
            try {
                output = BufferedOutputStream(
                    Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                )
                activePath = candidate.toAbsolutePath().normalize()
                groupTime = candidateTime
                activeDate = date
                rotationIndex = 0
                activeSize = 0
                formatterKey = null
                metrics.activeFileSize(activeSize)
                metrics.rotation(reason)
                return
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                candidateTime = if (candidateTime.toLocalTime() < LAST_FILE_TIME) {
                    candidateTime.plusNanos(1_000_000)
                } else {
                    date.atStartOfDay()
                }
            }
        }
    }

    private fun rotate(reason: String) {
        closeOutput()
        val currentGroupTime = requireNotNull(groupTime)
        val date = requireNotNull(activeDate)
        val directory = properties.rootPath
            .resolve(date.format(DateTimeFormatter.ofPattern("yyyy")))
            .resolve(date.format(DateTimeFormatter.ofPattern("MM")))
            .resolve(date.format(DateTimeFormatter.ofPattern("dd")))
        require(isSafeDirectory(directory)) { "Log directory must not be a link or reparse point: $directory" }
        var candidateIndex = rotationIndex + 1
        while (true) {
            val candidate = directory.resolve("${currentGroupTime.format(FILE_TIME_FORMAT)}-$candidateIndex.log")
            try {
                output = BufferedOutputStream(
                    Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                )
                activePath = candidate.toAbsolutePath().normalize()
                rotationIndex = candidateIndex
                activeSize = 0
                metrics.activeFileSize(activeSize)
                metrics.rotation(reason)
                return
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                candidateIndex++
            }
        }
    }

    private fun closeOutput() {
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null
    }

    /** Creates only missing path components and verifies each component without following links. */
    private fun ensureDirectory(directory: Path) {
        require(directory.startsWith(properties.rootPath)) { "Log directory is outside the configured storage path" }
        var current = properties.rootPath
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current.parent?.let { Files.createDirectories(it) }
            try {
                Files.createDirectory(current)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another component may have created the root between exists and create.
            }
        }
        require(isSafeDirectory(current)) { "Log directory must not be a link or reparse point: $current" }
        properties.rootPath.relativize(directory).forEach { component ->
            val next = current.resolve(component.toString())
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // Another writer may have created this date component concurrently.
                }
            }
            require(isSafeDirectory(next)) { "Log directory must not be a link or reparse point: $next" }
            current = next
        }
    }

    private fun isSafeDirectory(directory: Path): Boolean {
        var current: Path? = directory
        while (current != null && current.startsWith(properties.rootPath)) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(current) ||
                isOtherFileType(current) ||
                isReparsePoint(current)
            ) {
                return false
            }
            if (current == properties.rootPath) return true
            current = current.parent
        }
        return false
    }

    private fun isReparsePoint(path: Path): Boolean = runCatching {
        val attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int
        attributes and WINDOWS_REPARSE_POINT != 0
    }.getOrDefault(false)

    private fun isOtherFileType(path: Path): Boolean = runCatching {
        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
    }.getOrDefault(true)

    companion object {
        val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS")
        private val LAST_FILE_TIME: LocalTime = LocalTime.of(23, 59, 59, 999_000_000)
        private const val WINDOWS_REPARSE_POINT = 0x400
    }
}
