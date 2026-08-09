package top.foxball.shopmall.logging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RotatingLogFileWriterTest {
    @TempDir
    lateinit var temporaryDirectory: Path

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
    fun `writer uses date directory and rotates before configured limit`() {
        val properties = LoggingProperties(
            storagePath = temporaryDirectory.toString(),
            maxFileSize = org.springframework.util.unit.DataSize.ofMegabytes(10),
            maxRecordSize = org.springframework.util.unit.DataSize.ofKilobytes(256),
        )
        val writer = RotatingLogFileWriter(
            properties,
            LoggingMetrics(SimpleMeterRegistry()),
            null,
            Clock.fixed(Instant.parse("2026-08-08T11:25:14.238Z"), ZoneId.of("UTC")),
        )
        writer.initialize()
        val timestamp = LocalDateTime.parse("2026-08-08T19:25:14.238")
        val large = RenderedLogRecord("x", ByteArray(5 * 1024 * 1024) { 'x'.code.toByte() }, false)

        writer.write(large, timestamp, "template-a", LogLevel.INFO)
        writer.write(large, timestamp, "template-a", LogLevel.INFO)
        writer.write(large, timestamp, "template-a", LogLevel.INFO)
        writer.close()

        val files = Files.walk(temporaryDirectory).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        assertEquals(2, files.size)
        assertTrue(files.all { Files.size(it) <= LoggingProperties.MAX_FILE_SIZE_BYTES })
        assertTrue(files.all { it.toString().replace('\\', '/').contains("/2026/08/08/") })
        assertTrue(files.any { it.fileName.toString().endsWith("-1.log") })
    }

    @Test
    fun `template change rotates but level change does not`() {
        val properties = LoggingProperties(storagePath = temporaryDirectory.toString())
        val writer = RotatingLogFileWriter(properties, LoggingMetrics(SimpleMeterRegistry()), null)
        writer.initialize()
        val timestamp = LocalDateTime.parse("2026-08-08T19:25:14.238")
        val line = RenderedLogRecord("line", "line\n".toByteArray(), false)

        writer.write(line, timestamp, "template-a", LogLevel.INFO)
        writer.write(line, timestamp, "template-a", LogLevel.ERROR)
        assertEquals(0, writer.activeFile().rotationIndex)
        writer.write(line, timestamp, "template-b", LogLevel.ERROR)
        assertEquals(1, writer.activeFile().rotationIndex)
        writer.close()
    }

    @Test
    fun `file group time and filename use millisecond precision`() {
        val properties = LoggingProperties(storagePath = temporaryDirectory.toString())
        val writer = RotatingLogFileWriter(
            properties,
            LoggingMetrics(SimpleMeterRegistry()),
            null,
            Clock.fixed(Instant.parse("2026-08-08T11:25:14.238123456Z"), ZoneId.of("UTC")),
        )

        writer.initialize()

        val active = writer.activeFile()
        assertEquals(LocalDateTime.parse("2026-08-08T19:25:14.238"), active.fileTime)
        assertTrue(active.relativePath!!.endsWith("/20260808T192514.238-0.log"))
        writer.close()
    }

    @Test
    fun `writer rejects a symbolic link storage root`() {
        val target = temporaryDirectory.resolve("root-target")
        Files.createDirectories(target)
        val rootLink = temporaryDirectory.resolve("root-link")
        createSymbolicLinkOrSkip(rootLink, target)
        val properties = LoggingProperties(storagePath = rootLink.toString())
        val writer = RotatingLogFileWriter(properties, LoggingMetrics(SimpleMeterRegistry()), null)

        assertFailsWith<IllegalStateException> { writer.initialize() }

        assertEquals(0L, Files.list(target).use { paths -> paths.count() })
    }

    @Test
    fun `writer rejects a symbolic link date directory`() {
        val root = temporaryDirectory.resolve("logs")
        val dateParent = root.resolve("2026").resolve("08")
        Files.createDirectories(dateParent)
        val target = temporaryDirectory.resolve("date-target")
        Files.createDirectories(target)
        createSymbolicLinkOrSkip(dateParent.resolve("08"), target)
        val properties = LoggingProperties(storagePath = root.toString())
        val writer = RotatingLogFileWriter(
            properties,
            LoggingMetrics(SimpleMeterRegistry()),
            null,
            Clock.fixed(Instant.parse("2026-08-08T11:25:14.238Z"), ZoneId.of("UTC")),
        )

        assertFailsWith<IllegalStateException> { writer.initialize() }

        assertEquals(0L, Files.list(target).use { paths -> paths.count() })
    }
}
