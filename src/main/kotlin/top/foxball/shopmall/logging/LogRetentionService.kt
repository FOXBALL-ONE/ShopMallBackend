package top.foxball.shopmall.logging

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Removes only expired, completed date folders below the configured logging root. */
@Component
class LogRetentionService(
    private val properties: LoggingProperties,
    private val runtimeLoggingManager: RuntimeLoggingManager,
    private val historyService: LogHistoryService,
    @Qualifier(LoggingConfig.LOGGING_TASK_SCHEDULER)
    private val loggingTaskScheduler: ThreadPoolTaskScheduler,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Run once after all beans are ready so stale files are not retained until 03:15. */
    @EventListener(ApplicationReadyEvent::class)
    fun removeExpiredLogsAtStartup() {
        runCatching {
            loggingTaskScheduler.execute { removeExpiredLogsSafely("startup") }
        }.onFailure { exception ->
            log.warn("Unable to schedule ShopMall startup log retention cleanup: {}", exception.message)
        }
    }

    @Scheduled(
        cron = "0 15 3 * * *",
        zone = "\${shopmall.logging.time-zone:Asia/Shanghai}",
        scheduler = LoggingConfig.LOGGING_TASK_SCHEDULER,
    )
    fun removeExpiredLogs() {
        removeExpiredLogsSafely("scheduled")
    }

    private fun removeExpiredLogsSafely(trigger: String) {
        runCatching { removeExpiredLogsNow() }
            .onFailure { exception ->
                // Retention is maintenance; a malformed or temporarily unavailable storage
                // directory must not prevent the application or later schedules from running.
                log.warn("ShopMall log retention cleanup failed ({}): {}", trigger, exception.message)
            }
    }

    private fun removeExpiredLogsNow() {
        val cutoff = LocalDate.now(properties.zoneId).minusDays(properties.retentionDays.toLong() - 1)
        if (!isSafeDirectory(properties.rootPath)) return
        Files.newDirectoryStream(properties.rootPath).use { years ->
            years.filter(::isSafeDirectory)
                .forEach { year ->
                    Files.newDirectoryStream(year).use { months ->
                        months.filter(::isSafeDirectory)
                            .forEach { month ->
                                Files.newDirectoryStream(month).use { days ->
                                    days.filter(::isSafeDirectory)
                                        .forEach { day -> deleteExpiredDay(day, cutoff) }
                                }
                            }
                    }
                }
        }
    }

    private fun deleteExpiredDay(day: Path, cutoff: LocalDate) {
        val date = runCatching {
            LocalDate.parse(
                "${day.parent.parent.fileName}-${day.parent.fileName}-${day.fileName}",
                DateTimeFormatter.ISO_LOCAL_DATE,
            )
        }.getOrNull() ?: return
        if (!date.isBefore(cutoff)) return
        val active = runtimeLoggingManager.snapshot().activeFile.relativePath
        Files.newDirectoryStream(day).use { files ->
            files.filter { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path) &&
                    !isReparsePoint(path) &&
                    LogHistoryService.FILE_PATTERN.matches(path.fileName.toString()) &&
                    active != relativePath(path)
            }.forEach { historyService.deleteIfNotBeingRead(it) }
        }
        runCatching { Files.deleteIfExists(day) }
        runCatching { Files.deleteIfExists(day.parent) }
        runCatching { Files.deleteIfExists(day.parent.parent) }
    }

    private fun relativePath(path: Path): String = properties.rootPath.relativize(path.toAbsolutePath().normalize())
        .toString()
        .replace('\\', '/')

    private fun isSafeDirectory(path: Path): Boolean =
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path) &&
            !isOtherFileType(path) &&
            !isReparsePoint(path)

    private fun isReparsePoint(path: Path): Boolean = runCatching {
        val attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int
        attributes and WINDOWS_REPARSE_POINT != 0
    }.getOrDefault(false)

    private fun isOtherFileType(path: Path): Boolean = runCatching {
        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
    }.getOrDefault(true)

    private companion object {
        const val WINDOWS_REPARSE_POINT = 0x400
    }
}
