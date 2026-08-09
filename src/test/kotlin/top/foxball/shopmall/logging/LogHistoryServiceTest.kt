package top.foxball.shopmall.logging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.service.AdminAccessService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogHistoryServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private fun service(properties: LoggingProperties): LogHistoryService {
        val adminAccess = mock(AdminAccessService::class.java)
        val runtime = mock(RuntimeLoggingManager::class.java)
        `when`(runtime.snapshot()).thenReturn(
            RuntimeLoggingSnapshot(0, RuntimeLoggingStatus.UP, ActiveLogFile(null, 0, null, null, null, null), null),
        )
        return LogHistoryService(properties, adminAccess, runtime, LoggingMetrics(SimpleMeterRegistry()))
    }

    private fun createDirectory(date: LocalDate): Path {
        val directory = temporaryDirectory
            .resolve(date.format(LogHistoryService.YEAR_FORMAT))
            .resolve(date.format(LogHistoryService.MONTH_FORMAT))
            .resolve(date.format(LogHistoryService.DAY_FORMAT))
        Files.createDirectories(directory)
        return directory
    }

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath())
        } catch (exception: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are not supported: ${exception.message}")
        } catch (exception: SecurityException) {
            assumeTrue(false, "Symbolic links are not permitted: ${exception.message}")
        } catch (exception: IOException) {
            assumeTrue(false, "Symbolic links are not available: ${exception.message}")
        }
    }

    @Test
    fun `history reads only complete lines and rejects outside identifiers`() = runBlocking {
        val properties = LoggingProperties(storagePath = temporaryDirectory.toString())
        val service = service(properties)
        val date = LocalDate.now(properties.zoneId)
        val fileTime = date.atTime(19, 25, 14, 238_000_000)
        val directory = createDirectory(date)
        val path = directory.resolve("${fileTime.format(RotatingLogFileWriter.FILE_TIME_FORMAT)}-0.log")
        Files.writeString(path, "first line\nsecond line\npartial")

        val page = service.readContent(99, date, fileTime, 0, 3, false, null, 10)

        assertEquals(listOf("second line"), page.lines.map { it.text })
        assertEquals(false, page.eof)
        assertFailsWith<IllegalArgumentException> {
            service.readContent(99, date, fileTime.plusDays(1), 0, null, false, null, 10)
        }
    }

    @Test
    fun `retention boundary includes the configured number of calendar days`() = runBlocking {
        val properties = LoggingProperties(storagePath = temporaryDirectory.toString(), retentionDays = 3)
        val service = service(properties)
        val today = LocalDate.now(properties.zoneId)
        val oldestReadableDate = today.minusDays(properties.retentionDays.toLong() - 1)

        val page = service.listFiles(99, oldestReadableDate, 0, 10)

        assertTrue(page.files.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            service.listFiles(99, today.minusDays(properties.retentionDays.toLong()), 0, 10)
        }
        assertFailsWith<IllegalArgumentException> {
            service.listFiles(99, today.plusDays(1), 0, 10)
        }
    }

    @Test
    fun `history identifiers require millisecond precision and a nonnegative rotation`() = runBlocking {
        val properties = LoggingProperties(storagePath = temporaryDirectory.toString())
        val service = service(properties)
        val date = LocalDate.now(properties.zoneId)
        val fileTime = date.atTime(19, 25, 14, 238_000_000)

        assertFailsWith<IllegalArgumentException> {
            service.readContent(99, date, fileTime.plusNanos(1), 0, null, false, null, 10)
        }
        assertFailsWith<IllegalArgumentException> {
            service.readContent(99, date, fileTime, -1, null, false, null, 10)
        }
    }

    @Test
    fun `history rejects symbolic link directories and files`() = runBlocking {
        val properties = LoggingProperties(storagePath = temporaryDirectory.toString())
        val service = service(properties)
        val today = LocalDate.now(properties.zoneId)
        val directoryLinkDate = today
        val directoryFileTime = directoryLinkDate.atTime(10, 0, 0, 123_000_000)
        val directoryParent = temporaryDirectory
            .resolve(directoryLinkDate.format(LogHistoryService.YEAR_FORMAT))
            .resolve(directoryLinkDate.format(LogHistoryService.MONTH_FORMAT))
        Files.createDirectories(directoryParent)
        val directoryTarget = temporaryDirectory.resolve("directory-link-target")
        Files.createDirectories(directoryTarget)
        Files.writeString(
            directoryTarget.resolve("${directoryFileTime.format(RotatingLogFileWriter.FILE_TIME_FORMAT)}-0.log"),
            "outside\n",
        )
        createSymbolicLinkOrSkip(
            directoryParent.resolve(directoryLinkDate.format(LogHistoryService.DAY_FORMAT)),
            directoryTarget,
        )

        assertTrue(service.listFiles(99, directoryLinkDate, 0, 10).files.isEmpty())
        assertFailsWith<ResourceNotFoundException> {
            service.readContent(99, directoryLinkDate, directoryFileTime, 0, null, false, null, 10)
        }

        val fileLinkDate = today.minusDays(1)
        val fileTime = fileLinkDate.atTime(11, 0, 0, 456_000_000)
        val fileDirectory = createDirectory(fileLinkDate)
        val fileTarget = temporaryDirectory.resolve("file-link-target.log")
        Files.writeString(fileTarget, "outside\n")
        createSymbolicLinkOrSkip(
            fileDirectory.resolve("${fileTime.format(RotatingLogFileWriter.FILE_TIME_FORMAT)}-0.log"),
            fileTarget,
        )

        assertTrue(service.listFiles(99, fileLinkDate, 0, 10).files.isEmpty())
        assertFailsWith<ResourceNotFoundException> {
            service.readContent(99, fileLinkDate, fileTime, 0, null, false, null, 10)
        }
    }

    @Test
    fun `history reads a complete line that crosses the internal buffer boundary`() = runBlocking {
        val properties = LoggingProperties(storagePath = temporaryDirectory.toString())
        val service = service(properties)
        val date = LocalDate.now(properties.zoneId)
        val fileTime = date.atTime(20, 10, 0, 789_000_000)
        val directory = createDirectory(date)
        val path = directory.resolve("${fileTime.format(RotatingLogFileWriter.FILE_TIME_FORMAT)}-0.log")
        val longLine = "x".repeat(LogHistoryService.READ_BUFFER_BYTES + 73)
        Files.writeString(path, "$longLine\nsecond\n")

        val page = service.readContent(99, date, fileTime, 0, null, false, null, 2)

        assertEquals(listOf(longLine, "second"), page.lines.map { line -> line.text })
        assertTrue(page.eof)
    }
}
