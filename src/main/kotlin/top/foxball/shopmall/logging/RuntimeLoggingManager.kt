package top.foxball.shopmall.logging

import ch.qos.logback.classic.LoggerContext
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Applies validated settings to the local Logback context and owns the ShopMall append-only sink. */
@Component
class RuntimeLoggingManager(
    private val properties: LoggingProperties,
    private val settingsService: LoggingSettingsService,
    private val liveBuffer: LiveLogBuffer,
    private val metrics: LoggingMetrics,
) {
    private val lock = ReentrantLock()
    private val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext
        ?: throw IllegalStateException("ShopMall logging requires Logback LoggerContext")
    private val writer = RotatingLogFileWriter(properties, metrics, loggerContext.statusManager)
    private val overriddenBaselineLevels = LinkedHashMap<String, ch.qos.logback.classic.Level?>()
    private var appender: ShopMallLogAppender? = null
    @Volatile
    private var appliedSettings: LoggingSettings = defaultSettings()
    @Volatile
    private var runtimeStatus = RuntimeLoggingStatus.UP
    @Volatile
    private var lastError: String? = null

    @PostConstruct
    fun initialize() {
        lock.withLock {
            val defaults = defaultSettings()
            val formatter = RuntimeLogFormatter.compile(defaults.outputTemplate)
            val sink = ShopMallLogAppender(writer, liveBuffer, properties, metrics, formatter, defaults.version).apply {
                context = loggerContext
                name = APPENDER_NAME
                start()
            }
            loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(sink)
            appender = sink
            applyLocked(defaults)
        }
    }

    fun apply(settings: LoggingSettings) {
        lock.withLock {
            val current = appliedSettings
            if (settings.settingsId == current.settingsId && settings.version < current.version) {
                // A delayed Redis read or Pub/Sub callback must never roll a node back to an
                // older snapshot that was already applied locally.
                return@withLock
            }
            applyLocked(settings)
        }
    }

    fun snapshot(): RuntimeLoggingSnapshot = RuntimeLoggingSnapshot(
        effectiveVersion = appliedSettings.version,
        status = runtimeStatus,
        activeFile = writer.activeFile(),
        lastError = lastError,
    )

    fun currentSettings(): LoggingSettings = appliedSettings

    @Scheduled(
        fixedDelayString = "\${shopmall.logging.reconcile-interval-millis:5000}",
        scheduler = LoggingConfig.LOGGING_TASK_SCHEDULER,
    )
    fun reconcile() {
        try {
            val settings = settingsService.getSettings()
            if (settings.settingsId != appliedSettings.settingsId || settings.version != appliedSettings.version) {
                apply(settings)
            } else {
                // A transient Redis outage must not leave a healthy, already-applied snapshot
                // permanently marked as degraded after the next successful reconciliation.
                runtimeStatus = RuntimeLoggingStatus.UP
                lastError = null
            }
        } catch (exception: Exception) {
            runtimeStatus = RuntimeLoggingStatus.DEGRADED
            lastError = exception.message ?: exception.javaClass.simpleName
        }
    }

    @PreDestroy
    fun close() {
        lock.withLock {
            val root = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            appender?.let(root::detachAppender)
            val sink = appender
            sink?.stop()
            appender = null
            if (sink == null) writer.close()
        }
    }

    private fun applyLocked(settings: LoggingSettings) {
        try {
            val formatter = RuntimeLogFormatter.compile(settings.outputTemplate)
            val root = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            root.level = settings.rootLevel.toLogbackLevel()

            appliedSettings.loggerLevels.keys.minus(settings.loggerLevels.keys).forEach { name ->
                loggerContext.getLogger(name).level = overriddenBaselineLevels[name]
            }
            settings.loggerLevels.forEach { (name, level) ->
                val logger = loggerContext.getLogger(name)
                overriddenBaselineLevels.putIfAbsent(name, logger.level)
                logger.level = level.toLogbackLevel()
            }

            val audit = loggerContext.getLogger(LoggingSettingsService.AUDIT_LOGGER)
            audit.level = LogLevel.INFO.toLogbackLevel()
            appender?.updateFormatter(formatter, settings.version)
            appliedSettings = settings
            runtimeStatus = RuntimeLoggingStatus.UP
            lastError = null
        } catch (exception: Exception) {
            runtimeStatus = RuntimeLoggingStatus.DEGRADED
            lastError = exception.message ?: exception.javaClass.simpleName
            throw exception
        }
    }

    private fun defaultSettings() = LoggingSettings(
        rootLevel = LogLevel.parse(properties.defaultRootLevel),
        loggerLevels = emptyMap(),
        outputTemplate = properties.defaultOutputTemplate,
        version = 0,
        source = LoggingSettingsSource.DEFAULT,
        updatedAt = null,
        updatedBy = null,
        settingsId = "default",
    )

    private companion object {
        const val APPENDER_NAME = "SHOPMALL_FILE_AND_LIVE"
    }
}
