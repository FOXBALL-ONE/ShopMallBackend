package top.foxball.shopmall.logging

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import top.foxball.shopmall.service.AdminAccessService
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Redis-backed, optimistic runtime logging settings. Redis is never consulted on the event path. */
@Service
class LoggingSettingsService(
    private val redis: StringRedisTemplate,
    private val properties: LoggingProperties,
    private val adminAccessService: AdminAccessService,
    private val objectMapper: ObjectMapper,
    private val metrics: LoggingMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastInvalidSettingsFingerprint = AtomicReference<String?>(null)

    fun getSettings(adminId: Long): LoggingSettings {
        adminAccessService.requireAdmin(adminId)
        return getSettings()
    }

    fun getSettings(): LoggingSettings {
        val values = try {
            redis.opsForHash<String, String>().multiGet(SETTINGS_KEY, SETTINGS_FIELDS)
        } catch (exception: RuntimeException) {
            throw LoggingUnavailableException("Unable to read logging settings", exception)
        } ?: throw LoggingUnavailableException("Redis returned no logging settings result")
        if (values.size != SETTINGS_FIELDS.size) {
            throw LoggingUnavailableException("Redis returned an incomplete logging settings result")
        }
        if (values.all { it == null }) {
            val exists = try {
                redis.hasKey(SETTINGS_KEY)
            } catch (exception: RuntimeException) {
                throw LoggingUnavailableException("Unable to verify logging settings", exception)
            }
            if (!exists) {
                lastInvalidSettingsFingerprint.set(null)
                return defaultSettings()
            }
        }
        return try {
            parseStoredSettings(SETTINGS_FIELDS.zip(values).toMap()).also {
                lastInvalidSettingsFingerprint.set(null)
            }
        } catch (exception: Exception) {
            val fingerprint = "${exception::class.qualifiedName}:${exception.message}"
            if (lastInvalidSettingsFingerprint.getAndSet(fingerprint) != fingerprint) {
                log.error("Stored logging settings are invalid ({})", exception::class.simpleName)
            }
            throw LoggingUnavailableException("Stored logging settings are invalid", exception)
        }
    }

    fun updateSettings(
        adminId: Long,
        command: UpdateLoggingSettingsCommand,
    ): LoggingSettingsUpdateResult {
        adminAccessService.requireAdmin(adminId)
        require(command.expectedVersion >= 0) { "expected_version must not be negative" }
        val rootLevel = LogLevel.parse(command.rootLevel)
        val overrides = parseOverrides(command.loggerOverrides)
        RuntimeLogFormatter.compile(command.outputTemplate)

        val rawOverrides = try {
            objectMapper.writeValueAsString(overrides.mapValues { it.value.name }.toSortedMap())
        } catch (exception: Exception) {
            throw IllegalArgumentException("Unable to encode logger overrides", exception)
        }
        val updatedAt = LocalDateTime.now(properties.zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val result = try {
            redis.execute(
                updateSettingsScript,
                listOf(SETTINGS_KEY),
                command.expectedVersion.toString(),
                rootLevel.name,
                rawOverrides,
                command.outputTemplate,
                UUID.randomUUID().toString(),
                updatedAt,
                adminId.toString(),
            )
        } catch (exception: RuntimeException) {
            throw LoggingUnavailableException("Unable to update logging settings", exception)
        } ?: throw LoggingUnavailableException("Redis returned no logging settings update result")

        val parts = result.split('|', limit = 3)
        return when (parts.firstOrNull()) {
            UPDATE_RESULT_UPDATED -> {
                if (parts.size != 3) throw LoggingUnavailableException("Redis returned an invalid logging settings update")
                val version = parts[1].toLongOrNull()?.takeIf { it > 0 }
                    ?: throw LoggingUnavailableException("Redis returned an invalid logging settings version")
                val settingsId = parts[2].takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                    ?: throw LoggingUnavailableException("Redis returned an invalid logging settings generation")
                metrics.settingsUpdated("success")
                LoggingSettingsUpdateResult.Updated(
                    LoggingSettings(
                        rootLevel = rootLevel,
                        loggerLevels = overrides,
                        outputTemplate = command.outputTemplate,
                        version = version,
                        source = LoggingSettingsSource.REDIS,
                        updatedAt = LocalDateTime.parse(updatedAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        updatedBy = adminId,
                        settingsId = settingsId,
                    ),
                )
            }

            UPDATE_RESULT_CONFLICT -> {
                val actualVersion = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw LoggingUnavailableException("Redis returned an invalid logging settings conflict")
                metrics.settingsUpdated("conflict")
                LoggingSettingsUpdateResult.Conflict(actualVersion)
            }

            else -> throw LoggingUnavailableException("Redis returned an unknown logging settings update result")
        }
    }

    fun preview(adminId: Long, outputTemplate: String): LoggingTemplatePreview {
        adminAccessService.requireAdmin(adminId)
        val formatter = RuntimeLogFormatter.compile(outputTemplate)
        val rendered = formatter.render(
            LogRecord(
                timestamp = LocalDateTime.of(2026, 8, 8, 19, 25, 14, 238_000_000),
                level = LogLevel.INFO,
                thread = "http-nio-8080-exec-1",
                logger = "top.foxball.shopmall.example.Logger",
                requestId = "preview-request-id",
                message = "Preview log message",
                exception = "",
            ),
            properties.maxRecordSize.toBytes().toInt(),
        )
        return LoggingTemplatePreview(rendered.text, rendered.bytes.size - 1)
    }

    private fun defaultSettings(): LoggingSettings = LoggingSettings(
        rootLevel = LogLevel.parse(properties.defaultRootLevel),
        loggerLevels = emptyMap(),
        outputTemplate = properties.defaultOutputTemplate,
        version = 0,
        source = LoggingSettingsSource.DEFAULT,
        updatedAt = null,
        updatedBy = null,
        settingsId = DEFAULT_SETTINGS_ID,
    )

    private fun parseStoredSettings(values: Map<String, String?>): LoggingSettings {
        val rawOverrides = values.getValue(LOGGER_LEVELS_FIELD)
            ?: throw IllegalArgumentException("Missing $LOGGER_LEVELS_FIELD")
        val node = objectMapper.readTree(rawOverrides)
        require(node.isObject) { "$LOGGER_LEVELS_FIELD must be a JSON object" }
        val overrides = node.properties().associate { (name, levelNode) ->
            name to LogLevel.parse(levelNode.asString())
        }
        validateLoggerOverrides(overrides)
        val rootLevel = LogLevel.parse(values.getValue(ROOT_LEVEL_FIELD) ?: throw IllegalArgumentException("Missing root level"))
        val outputTemplate = values.getValue(OUTPUT_TEMPLATE_FIELD)
            ?: throw IllegalArgumentException("Missing output template")
        RuntimeLogFormatter.compile(outputTemplate)
        val settingsId = values.getValue(SETTINGS_ID_FIELD)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: throw IllegalArgumentException("Missing or invalid settings_id")
        val version = values.getValue(VERSION_FIELD)?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Missing or invalid version")
        val updatedAt = values.getValue(UPDATED_AT_FIELD)
            ?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
            ?: throw IllegalArgumentException("Missing updated_at")
        val updatedBy = values.getValue(UPDATED_BY_FIELD)?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Missing or invalid updated_by")
        return LoggingSettings(
            rootLevel = rootLevel,
            loggerLevels = overrides.toSortedMap(),
            outputTemplate = outputTemplate,
            version = version,
            source = LoggingSettingsSource.REDIS,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
            settingsId = settingsId,
        )
    }

    private fun parseOverrides(rawOverrides: List<String>): Map<String, LogLevel> {
        require(rawOverrides.size <= MAX_LOGGER_OVERRIDES) {
            "logger_override supports at most $MAX_LOGGER_OVERRIDES entries"
        }
        val parsed = LinkedHashMap<String, LogLevel>()
        rawOverrides.forEach { raw ->
            val separator = raw.lastIndexOf('=')
            require(separator > 0 && separator < raw.length - 1) {
                "logger_override entries must use logger_name=LEVEL"
            }
            val name = raw.substring(0, separator).trim()
            val level = LogLevel.parse(raw.substring(separator + 1))
            require(parsed.put(name, level) == null) { "logger_override contains duplicate logger: $name" }
        }
        validateLoggerOverrides(parsed)
        return parsed.toSortedMap()
    }

    private fun validateLoggerOverrides(overrides: Map<String, LogLevel>) {
        require(overrides.size <= MAX_LOGGER_OVERRIDES) {
            "logger_override supports at most $MAX_LOGGER_OVERRIDES entries"
        }
        overrides.forEach { (name, _) ->
            require(name.length <= MAX_LOGGER_NAME_LENGTH) {
                "logger name must not exceed $MAX_LOGGER_NAME_LENGTH characters"
            }
            require(LOGGER_NAME_PATTERN.matches(name)) { "Invalid logger name: $name" }
            require(name != AUDIT_LOGGER) { "The audit logger has a fixed INFO level and cannot be overridden" }
            require(name != org.slf4j.Logger.ROOT_LOGGER_NAME) { "Use root_level to configure the root logger" }
        }
    }

    companion object {
        const val AUDIT_LOGGER = "top.foxball.shopmall.logging.audit"
        const val SETTINGS_KEY = "logging:settings:v1"
        const val SETTINGS_CHANGED_CHANNEL = "logging:settings:changed"
        const val MAX_LOGGER_NAME_LENGTH = 200
        private const val ROOT_LEVEL_FIELD = "root_level"
        private const val LOGGER_LEVELS_FIELD = "logger_levels"
        private const val OUTPUT_TEMPLATE_FIELD = "output_template"
        private const val SETTINGS_ID_FIELD = "settings_id"
        private const val VERSION_FIELD = "version"
        private const val UPDATED_AT_FIELD = "updated_at"
        private const val UPDATED_BY_FIELD = "updated_by"
        private const val DEFAULT_SETTINGS_ID = "default"
        private const val UPDATE_RESULT_UPDATED = "1"
        private const val UPDATE_RESULT_CONFLICT = "0"
        private const val MAX_LOGGER_OVERRIDES = 50
        private val LOGGER_NAME_PATTERN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
        private val SETTINGS_FIELDS = listOf(
            ROOT_LEVEL_FIELD,
            LOGGER_LEVELS_FIELD,
            OUTPUT_TEMPLATE_FIELD,
            SETTINGS_ID_FIELD,
            VERSION_FIELD,
            UPDATED_AT_FIELD,
            UPDATED_BY_FIELD,
        )
        private val updateSettingsScript = DefaultRedisScript(
            """
                local exists = redis.call('EXISTS', KEYS[1])
                local actual
                local settingsId
                if exists == 1 then
                    actual = tonumber(redis.call('HGET', KEYS[1], 'version'))
                    settingsId = redis.call('HGET', KEYS[1], 'settings_id')
                    if not actual or actual < 1 or not settingsId then return 'E' end
                else
                    actual = 0
                    settingsId = ARGV[5]
                end
                if actual ~= tonumber(ARGV[1]) then return '0|' .. actual end
                local nextVersion = actual + 1
                redis.call('HSET', KEYS[1],
                    'root_level', ARGV[2],
                    'logger_levels', ARGV[3],
                    'output_template', ARGV[4],
                    'settings_id', settingsId,
                    'version', nextVersion,
                    'updated_at', ARGV[6],
                    'updated_by', ARGV[7])
                redis.call('PUBLISH', 'logging:settings:changed', settingsId .. '|' .. nextVersion)
                return '1|' .. nextVersion .. '|' .. settingsId
            """.trimIndent(),
            String::class.java,
        )
    }
}
