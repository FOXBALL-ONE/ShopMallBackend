package top.foxball.shopmall.logging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.service.AdminAccessService
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.math.min

/** Safely indexes and reads only files produced by [RotatingLogFileWriter]. */
@Service
class LogHistoryService(
    private val properties: LoggingProperties,
    private val adminAccessService: AdminAccessService,
    private val runtimeLoggingManager: RuntimeLoggingManager,
    private val metrics: LoggingMetrics,
) {
    /** Caps administrator-triggered filesystem work so it cannot flood the shared IO pool. */
    private val historyIoDispatcher = Dispatchers.IO.limitedParallelism(HISTORY_IO_PARALLELISM)

    /** Counts short-lived readers atomically so retention cannot race a lease release. */
    private val activeReads = ConcurrentHashMap<Path, Int>()
    private val readLeaseMonitor = Any()

    suspend fun listDates(adminId: Long, fromDate: LocalDate?, toDate: LocalDate?): List<LogDateSummary> =
        withContext(historyIoDispatcher) {
        adminAccessService.requireAdmin(adminId)
        val from = fromDate ?: LocalDate.now(properties.zoneId).minusDays(properties.retentionDays.toLong() - 1)
        val today = LocalDate.now(properties.zoneId)
        val retentionStart = today.minusDays(properties.retentionDays.toLong() - 1)
        val to = toDate ?: today
        require(!from.isAfter(to)) { "from_date must not be after to_date" }
        require(!to.isAfter(today)) { "to_date must not be in the future" }
        require(!from.isBefore(retentionStart)) {
            "from_date exceeds the configured retention period"
        }
        require(!from.isBefore(to.minusDays(properties.retentionDays.toLong() - 1))) {
            "Requested date range exceeds the configured retention period"
        }
        val summaries = generateSequence(from) { date -> date.plusDays(1).takeIf { !it.isAfter(to) } }
            .mapNotNull { date ->
                val files = scanDateFiles(date)
                files.takeIf { it.isNotEmpty() }?.let { valid ->
                    LogDateSummary(date, valid.size, valid.sumOf { it.sizeBytes })
                }
            }
            .toList()
            .sortedByDescending { it.date }
        metrics.historyRead("success")
        summaries
    }

    suspend fun listFiles(adminId: Long, date: LocalDate, cursor: Int, limit: Int): HistoryFilePage =
        withContext(historyIoDispatcher) {
        adminAccessService.requireAdmin(adminId)
        validateReadableDate(date)
        require(cursor >= 0) { "cursor must not be negative" }
        require(limit in 1..500) { "limit must be between 1 and 500" }
        val files = scanDateFiles(date).sortedWith(
            compareByDescending<HistoricalLogFile> { it.fileTime }.thenByDescending { it.rotationIndex },
        )
        val page = files.drop(cursor).take(limit)
        metrics.historyRead("success")
        HistoryFilePage(
            files = page,
            nextCursor = (cursor + page.size).takeIf { it < files.size },
        )
    }

    suspend fun readContent(
        adminId: Long,
        date: LocalDate,
        fileTime: LocalDateTime,
        rotationIndex: Int,
        afterOffset: Long?,
        tail: Boolean,
        query: String?,
        limit: Int,
    ): HistoryContentPage = withContext(historyIoDispatcher) {
        adminAccessService.requireAdmin(adminId)
        validateReadableDate(date)
        require(rotationIndex >= 0) { "rotation_index must not be negative" }
        require(limit in 1..500) { "limit must be between 1 and 500" }
        require(afterOffset == null || afterOffset >= 0) { "after_offset must not be negative" }
        require(query == null || query.length <= MAX_QUERY_LENGTH) { "query must not exceed $MAX_QUERY_LENGTH characters" }
        require(fileTime.toLocalDate() == date) { "file_time must be on the requested date" }
        require(fileTime.nano % NANOS_PER_MILLISECOND == 0) {
            "file_time must use millisecond precision"
        }
        if (tail) require(afterOffset == null) { "tail and after_offset cannot be used together" }

        val path = resolveFile(date, fileTime, rotationIndex)
        withReadLease(path) {
            val size = Files.size(path)
            val requestedOffset = when {
                afterOffset != null -> afterOffset.coerceAtMost(size)
                tail -> tailStartOffset(path, size, limit)
                else -> 0
            }
            val startOffset = normalizeLineStart(path, requestedOffset, size)
            val needle = query?.trim().orEmpty()
            val lines = ArrayList<HistoryLogLine>(limit)
            var position = startOffset
            var eof = true
            RandomAccessFile(path.toFile(), "r").use { file ->
                val reader = BufferedLogLineReader(file, size, startOffset)
                while (reader.position < size) {
                    val lineStart = reader.position
                    val bytes = reader.readLine()
                    if (bytes == null) {
                        eof = false
                        position = lineStart
                        break
                    }
                    position = reader.position
                    if (position - startOffset > MAX_READ_BYTES && lines.isNotEmpty()) {
                        position = lineStart
                        eof = false
                        break
                    }
                    val text = bytes.toString(StandardCharsets.UTF_8)
                    if (needle.isEmpty() || text.contains(needle, ignoreCase = true)) {
                        lines += HistoryLogLine(lineStart, position, text)
                    }
                    if (lines.size == limit || position - startOffset >= MAX_READ_BYTES) {
                        eof = reader.position >= size
                        break
                    }
                }
            }
            metrics.historyRead("success")
            HistoryContentPage(
                filename = path.name,
                fileSizeBytes = size,
                active = runtimeLoggingManager.snapshot().activeFile.relativePath == relativePath(path),
                lines = lines,
                nextOffset = position,
                eof = eof,
            )
        }
    }

    fun isBeingRead(path: Path): Boolean = synchronized(readLeaseMonitor) {
        activeReads[path.toAbsolutePath().normalize()]?.let { it > 0 } == true
    }

    /** Deletes a file only while holding the same monitor used to acquire/release read leases. */
    fun deleteIfNotBeingRead(path: Path): Boolean = synchronized(readLeaseMonitor) {
        val key = path.toAbsolutePath().normalize()
        if (activeReads[key]?.let { it > 0 } == true) return false
        Files.deleteIfExists(key)
    }

    private fun <T> withReadLease(path: Path, action: () -> T): T {
        val key = path.toAbsolutePath().normalize()
        synchronized(readLeaseMonitor) {
            if (!Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
                metrics.historyRead("not_found")
                throw ResourceNotFoundException("日志文件不存在")
            }
            activeReads.compute(key) { _, count -> (count ?: 0) + 1 }
        }
        try {
            return action()
        } finally {
            synchronized(readLeaseMonitor) {
                activeReads.compute(key) { _, count ->
                    when {
                        count == null || count <= 1 -> null
                        else -> count - 1
                    }
                }
            }
        }
    }

    private fun scanDateFiles(date: LocalDate): List<HistoricalLogFile> {
        val directory = dateDirectory(date)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (!isSafeDirectory(directory)) return emptyList()
        val activeRelativePath = runtimeLoggingManager.snapshot().activeFile.relativePath
        return Files.newDirectoryStream(directory).use { stream ->
            stream.mapNotNull { path -> parseFile(date, path, activeRelativePath) }.toList()
        }
    }

    private fun parseFile(date: LocalDate, path: Path, activeRelativePath: String?): HistoricalLogFile? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path) ||
            isOtherFileType(path) ||
            isReparsePoint(path)
        ) {
            return null
        }
        val match = FILE_PATTERN.matchEntire(path.fileName.toString()) ?: return null
        val fileTime = runCatching { LocalDateTime.parse(match.groupValues[1], RotatingLogFileWriter.FILE_TIME_FORMAT) }.getOrNull()
            ?: return null
        if (fileTime.toLocalDate() != date) return null
        val rotationIndex = match.groupValues[2].toIntOrNull() ?: return null
        val attributes = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (!attributes.isRegularFile) return null
        return HistoricalLogFile(
            date = date,
            fileTime = fileTime,
            rotationIndex = rotationIndex,
            filename = path.fileName.toString(),
            sizeBytes = attributes.size(),
            modifiedAt = LocalDateTime.ofInstant(attributes.lastModifiedTime().toInstant(), properties.zoneId),
            active = activeRelativePath == relativePath(path),
        )
    }

    private fun resolveFile(date: LocalDate, fileTime: LocalDateTime, rotationIndex: Int): Path {
        val filename = "${fileTime.format(RotatingLogFileWriter.FILE_TIME_FORMAT)}-$rotationIndex.log"
        require(FILE_PATTERN.matches(filename)) { "Invalid log file identifier" }
        val directory = dateDirectory(date)
        val path = directory.resolve(filename).toAbsolutePath().normalize()
        require(path.startsWith(properties.rootPath)) { "Log file is outside the configured storage path" }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path) ||
            isOtherFileType(path) ||
            isReparsePoint(path) ||
            !isSafeDirectory(directory)
        ) {
            metrics.historyRead("not_found")
            throw ResourceNotFoundException("日志文件不存在")
        }
        return path
    }

    private fun dateDirectory(date: LocalDate): Path = properties.rootPath
        .resolve(date.format(YEAR_FORMAT))
        .resolve(date.format(MONTH_FORMAT))
        .resolve(date.format(DAY_FORMAT))
        .toAbsolutePath()
        .normalize()

    private fun isSafeDirectory(directory: Path): Boolean {
        if (!directory.startsWith(properties.rootPath)) return false
        var current: Path? = directory
        while (current != null && current.startsWith(properties.rootPath)) {
            if (Files.isSymbolicLink(current) ||
                isOtherFileType(current) ||
                isReparsePoint(current) ||
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) {
                return false
            }
            if (current == properties.rootPath) break
            current = current.parent
        }
        return true
    }

    private fun tailStartOffset(path: Path, size: Long, limit: Int): Long {
        if (size == 0L) return 0
        val searchBytes = min(size, MAX_TAIL_SCAN_BYTES.toLong())
        val start = size - searchBytes
        RandomAccessFile(path.toFile(), "r").use { reader ->
            reader.seek(start)
            val bytes = ByteArray(searchBytes.toInt())
            reader.readFully(bytes)
            var newlines = 0
            for (index in bytes.indices.reversed()) {
                if (bytes[index] == '\n'.code.toByte() && ++newlines > limit) {
                    return start + index + 1
                }
            }
        }
        return start
    }

    /** A client cursor is trusted only as a byte position, never as a line boundary. */
    private fun normalizeLineStart(path: Path, offset: Long, snapshotSize: Long): Long {
        if (offset <= 0 || offset >= snapshotSize) return offset
        RandomAccessFile(path.toFile(), "r").use { reader ->
            reader.seek(offset - 1)
            if (reader.read() == '\n'.code) return offset
            reader.seek(offset)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var position = offset
            while (position < snapshotSize) {
                val read = reader.read(buffer, 0, min(buffer.size.toLong(), snapshotSize - position).toInt())
                if (read <= 0) break
                for (index in 0 until read) {
                    if (buffer[index] == '\n'.code.toByte()) return position + index + 1
                }
                position += read
            }
        }
        return snapshotSize
    }

    private class BufferedLogLineReader(
        private val reader: RandomAccessFile,
        private val snapshotSize: Long,
        startOffset: Long,
    ) {
        private val buffer = ByteArray(READ_BUFFER_BYTES)
        private var bufferIndex = 0
        private var bufferLimit = 0
        var position: Long = startOffset
            private set

        init {
            reader.seek(startOffset)
        }

        fun readLine(): ByteArray? {
            val output = ByteArrayOutputStream()
            while (position < snapshotSize) {
                if (bufferIndex >= bufferLimit) {
                    val read = reader.read(buffer, 0, min(buffer.size.toLong(), snapshotSize - position).toInt())
                    if (read <= 0) return null
                    bufferIndex = 0
                    bufferLimit = read
                }
                val available = min((bufferLimit - bufferIndex).toLong(), snapshotSize - position).toInt()
                var newlineIndex = -1
                for (index in bufferIndex until bufferIndex + available) {
                    if (buffer[index] == '\n'.code.toByte()) {
                        newlineIndex = index
                        break
                    }
                }
                if (newlineIndex >= 0) {
                    val length = newlineIndex - bufferIndex
                    if (output.size() + length > MAX_READ_BYTES) {
                        throw IllegalArgumentException("A log line exceeds the history read limit")
                    }
                    output.write(buffer, bufferIndex, length)
                    bufferIndex = newlineIndex + 1
                    position += length + 1L
                    return output.toByteArray()
                }
                if (output.size() + available > MAX_READ_BYTES) {
                    throw IllegalArgumentException("A log line exceeds the history read limit")
                }
                output.write(buffer, bufferIndex, available)
                bufferIndex += available
                position += available
            }
            return null
        }
    }

    private fun relativePath(path: Path): String = properties.rootPath.relativize(path.toAbsolutePath().normalize())
        .toString()
        .replace('\\', '/')

    private fun isReparsePoint(path: Path): Boolean = runCatching {
        val attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int
        attributes and WINDOWS_REPARSE_POINT != 0
    }.getOrDefault(false)

    private fun isOtherFileType(path: Path): Boolean = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
    }.getOrDefault(true)

    private fun validateReadableDate(date: LocalDate) {
        val today = LocalDate.now(properties.zoneId)
        require(!date.isAfter(today)) { "date must not be in the future" }
        require(!date.isBefore(today.minusDays(properties.retentionDays.toLong() - 1))) {
            "date exceeds the configured retention period"
        }
    }

    companion object {
        const val MAX_QUERY_LENGTH = 128
        const val MAX_READ_BYTES = 1_024 * 1_024
        const val MAX_TAIL_SCAN_BYTES = 1_024 * 1_024
        const val READ_BUFFER_BYTES = 16 * 1_024
        const val WINDOWS_REPARSE_POINT = 0x400
        const val NANOS_PER_MILLISECOND = 1_000_000
        private const val HISTORY_IO_PARALLELISM = 4
        val FILE_PATTERN = Regex("(\\d{8}T\\d{6}\\.\\d{3})-(0|[1-9]\\d*)\\.log")
        val YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM")
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd")
    }
}
