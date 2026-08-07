package top.foxball.shopmall.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import org.springframework.web.util.ServletRequestPathUtils
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import top.foxball.shopmall.service.AdminAccessService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the live rate-limit policy from Redis and performs optimistic, versioned updates.
 *
 * Quota values are deliberately never cached. The one-entry cache contains only compiled
 * path patterns, keyed by the persisted settings generation, version, and raw rule text.
 */
@Service
class RateLimitSettingsService(
    private val redis: StringRedisTemplate,
    private val properties: RateLimitProperties,
    private val adminAccessService: AdminAccessService,
    private val metrics: RateLimitMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val matcherCache = AtomicReference<CompiledMatchers?>(null)
    private val lastInvalidSettingsFingerprint = AtomicReference<String?>(null)

    fun getSettings(adminId: Long): RateLimitSettings {
        adminAccessService.requireAdmin(adminId)
        return getSettings()
    }

    /** Reads the current snapshot used by the request filter. */
    fun getSettings(): RateLimitSettings {
        val values = try {
            redis.opsForHash<String, String>().multiGet(SETTINGS_KEY, SETTINGS_FIELDS)
        } catch (exception: RuntimeException) {
            throw RateLimitUnavailableException("Unable to read rate-limit settings", exception)
        } ?: throw RateLimitUnavailableException("Redis returned no rate-limit settings result")

        if (values.size != SETTINGS_FIELDS.size) {
            throw RateLimitUnavailableException("Redis returned an incomplete rate-limit settings result")
        }
        if (values.all { it == null }) {
            val exists = try {
                redis.hasKey(SETTINGS_KEY)
            } catch (exception: RuntimeException) {
                throw RateLimitUnavailableException("Unable to verify rate-limit settings", exception)
            }
            if (!exists) return defaultSettings()
        }

        return try {
            parseStoredSettings(values)
        } catch (exception: Exception) {
            logInvalidStoredSettings(exception)
            throw RateLimitUnavailableException("Stored rate-limit settings are invalid", exception)
        }
    }

    fun updateSettings(
        adminId: Long,
        command: UpdateRateLimitSettingsCommand,
    ): RateLimitSettingsUpdateResult {
        adminAccessService.requireAdmin(adminId)
        require(command.authenticatedRequestsPerMinute in RateLimitProperties.MIN_REQUESTS_PER_MINUTE..RateLimitProperties.MAX_REQUESTS_PER_MINUTE) {
            "authenticated_requests_per_minute must be between ${RateLimitProperties.MIN_REQUESTS_PER_MINUTE} " +
                "and ${RateLimitProperties.MAX_REQUESTS_PER_MINUTE}"
        }
        require(command.anonymousRequestsPerMinute in RateLimitProperties.MIN_REQUESTS_PER_MINUTE..RateLimitProperties.MAX_REQUESTS_PER_MINUTE) {
            "anonymous_requests_per_minute must be between ${RateLimitProperties.MIN_REQUESTS_PER_MINUTE} " +
                "and ${RateLimitProperties.MAX_REQUESTS_PER_MINUTE}"
        }
        require(command.expectedVersion >= 0) { "expected_version must not be negative" }

        val normalizedPaths = RateLimitPathRules.normalize(command.excludedPaths)
        val previous = getSettings()
        val updatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val rawPaths = normalizedPaths.joinToString("\n")
        val result = try {
            redis.execute(
                updateSettingsScript,
                listOf(SETTINGS_KEY),
                command.expectedVersion.toString(),
                command.enabled.toString(),
                command.authenticatedRequestsPerMinute.toString(),
                command.anonymousRequestsPerMinute.toString(),
                rawPaths,
                UUID.randomUUID().toString(),
                updatedAt,
                adminId.toString(),
            )
        } catch (exception: RuntimeException) {
            throw RateLimitUnavailableException("Unable to update rate-limit settings", exception)
        } ?: throw RateLimitUnavailableException("Redis returned no rate-limit update result")

        val resultParts = result.split('|', limit = 3)
        return when (resultParts.firstOrNull()) {
            UPDATE_RESULT_UPDATED -> {
                if (resultParts.size != 3) {
                    throw RateLimitUnavailableException("Redis returned an invalid rate-limit update result")
                }
                val version = resultParts[1].toLongOrNull()?.takeIf { it > 0 }
                    ?: throw RateLimitUnavailableException("Redis returned an invalid rate-limit settings version")
                val settingsId = resultParts[2].takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                    ?: throw RateLimitUnavailableException("Redis returned an invalid rate-limit settings generation")
                val settings = RateLimitSettings(
                    enabled = command.enabled,
                    windowSeconds = properties.windowSeconds,
                    authenticatedRequestsPerMinute = command.authenticatedRequestsPerMinute,
                    anonymousRequestsPerMinute = command.anonymousRequestsPerMinute,
                    excludedPaths = normalizedPaths,
                    version = version,
                    source = RateLimitSettingsSource.REDIS,
                    updatedAt = LocalDateTime.parse(updatedAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    updatedBy = adminId,
                    settingsId = settingsId,
                    excludedPathsRaw = rawPaths,
                )
                metrics.settingsUpdated()
                log.info(
                    "Rate-limit settings updated by administrator {}: enabled {} -> {}, authenticated {} -> {}, anonymous {} -> {}, version {}",
                    adminId,
                    previous.enabled,
                    command.enabled,
                    previous.authenticatedRequestsPerMinute,
                    command.authenticatedRequestsPerMinute,
                    previous.anonymousRequestsPerMinute,
                    command.anonymousRequestsPerMinute,
                    version,
                )
                RateLimitSettingsUpdateResult.Updated(settings)
            }

            UPDATE_RESULT_CONFLICT -> {
                if (resultParts.size != 2) {
                    throw RateLimitUnavailableException("Redis returned an invalid rate-limit conflict result")
                }
                val actualVersion = resultParts[1].toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw RateLimitUnavailableException("Redis returned an invalid rate-limit conflict version")
                RateLimitSettingsUpdateResult.Conflict(actualVersion)
            }

            else -> throw RateLimitUnavailableException("Redis returned an unknown rate-limit update result")
        }
    }

    /** Returns true only for a valid current request path and a configured Spring PathPattern match. */
    fun matchesExcludedPath(settings: RateLimitSettings, request: HttpServletRequest): Boolean {
        val rawPath = request.requestURI.removePrefix(request.contextPath)
        if (!RateLimitPathRules.isCanonicalRequestPath(rawPath)) return false

        val patterns = try {
            compiledMatchers(settings).patterns
        } catch (exception: Exception) {
            throw RateLimitUnavailableException("Unable to compile rate-limit exclusion paths", exception)
        }
        if (patterns.isEmpty()) return false

        val path = try {
            ServletRequestPathUtils.parseAndCache(request)
            ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication()
        } catch (exception: Exception) {
            throw RateLimitUnavailableException("Unable to parse request path for rate limiting", exception)
        }
        return patterns.any { it.matches(path) }
    }

    private fun defaultSettings(): RateLimitSettings {
        val paths = RateLimitPathRules.normalize(properties.defaultExcludedPaths)
        val rawPaths = paths.joinToString("\n")
        return RateLimitSettings(
            enabled = properties.enabled,
            windowSeconds = properties.windowSeconds,
            authenticatedRequestsPerMinute = properties.defaultAuthenticatedRequestsPerMinute,
            anonymousRequestsPerMinute = properties.defaultAnonymousRequestsPerMinute,
            excludedPaths = paths,
            version = 0,
            source = RateLimitSettingsSource.DEFAULT,
            updatedAt = null,
            updatedBy = null,
            settingsId = DEFAULT_SETTINGS_ID,
            excludedPathsRaw = rawPaths,
        )
    }

    private fun parseStoredSettings(values: List<String?>): RateLimitSettings {
        val byField = SETTINGS_FIELDS.zip(values).toMap()
        // Hashes written before the runtime switch was introduced are safe to treat as enabled.
        // This preserves enforcement and lets the next successful admin update persist the field.
        val enabled = byField.getValue(ENABLED_FIELD)?.let(::parseEnabled) ?: true
        val authenticated = parseQuota(byField.getValue(AUTHENTICATED_FIELD), AUTHENTICATED_FIELD)
        val anonymous = parseQuota(byField.getValue(ANONYMOUS_FIELD), ANONYMOUS_FIELD)
        val rawPaths = byField.getValue(EXCLUDED_PATHS_FIELD)
            ?: throw IllegalArgumentException("Missing $EXCLUDED_PATHS_FIELD")
        val paths = RateLimitPathRules.parseStored(rawPaths)
        val settingsId = byField.getValue(SETTINGS_ID_FIELD)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: throw IllegalArgumentException("Missing or invalid $SETTINGS_ID_FIELD")
        val version = byField.getValue(VERSION_FIELD)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Missing or invalid $VERSION_FIELD")
        val updatedAtRaw = byField.getValue(UPDATED_AT_FIELD)
            ?: throw IllegalArgumentException("Missing $UPDATED_AT_FIELD")
        val updatedAt = LocalDateTime.parse(updatedAtRaw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val updatedBy = byField.getValue(UPDATED_BY_FIELD)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Missing or invalid $UPDATED_BY_FIELD")
        return RateLimitSettings(
            enabled = enabled,
            windowSeconds = properties.windowSeconds,
            authenticatedRequestsPerMinute = authenticated,
            anonymousRequestsPerMinute = anonymous,
            excludedPaths = paths,
            version = version,
            source = RateLimitSettingsSource.REDIS,
            updatedAt = updatedAt,
            updatedBy = updatedBy,
            settingsId = settingsId,
            excludedPathsRaw = rawPaths,
        )
    }

    private fun parseEnabled(raw: String): Boolean = when (raw) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid $ENABLED_FIELD")
    }

    private fun parseQuota(raw: String?, field: String): Int = raw
        ?.toIntOrNull()
        ?.takeIf { it in RateLimitProperties.MIN_REQUESTS_PER_MINUTE..RateLimitProperties.MAX_REQUESTS_PER_MINUTE }
        ?: throw IllegalArgumentException("Missing or invalid $field")

    private fun compiledMatchers(settings: RateLimitSettings): CompiledMatchers {
        val current = matcherCache.get()
        if (current != null && current.matches(settings)) return current

        val storedPaths = RateLimitPathRules.parseStored(settings.excludedPathsRaw)
        if (storedPaths != settings.excludedPaths) {
            throw IllegalArgumentException("Rate-limit settings path snapshot is inconsistent")
        }
        val parser = PathPatternParser()
        val replacement = CompiledMatchers(
            settingsId = settings.settingsId,
            version = settings.version,
            excludedPathsRaw = settings.excludedPathsRaw,
            patterns = storedPaths.map(parser::parse),
        )
        matcherCache.set(replacement)
        return replacement
    }

    private fun logInvalidStoredSettings(exception: Exception) {
        val fingerprint = "${exception::class.qualifiedName}:${exception.message}"
        if (lastInvalidSettingsFingerprint.getAndSet(fingerprint) != fingerprint) {
            log.error("Stored rate-limit settings are invalid: {}", exception.message)
        }
    }

    private data class CompiledMatchers(
        val settingsId: String,
        val version: Long,
        val excludedPathsRaw: String,
        val patterns: List<PathPattern>,
    ) {
        fun matches(settings: RateLimitSettings): Boolean =
            settingsId == settings.settingsId &&
                version == settings.version &&
                excludedPathsRaw == settings.excludedPathsRaw
    }

    private companion object {
        const val SETTINGS_KEY = "rate-limit:settings:v1"
        const val ENABLED_FIELD = "enabled"
        const val AUTHENTICATED_FIELD = "authenticated_requests_per_minute"
        const val ANONYMOUS_FIELD = "anonymous_requests_per_minute"
        const val EXCLUDED_PATHS_FIELD = "excluded_paths"
        const val SETTINGS_ID_FIELD = "settings_id"
        const val VERSION_FIELD = "version"
        const val UPDATED_AT_FIELD = "updated_at"
        const val UPDATED_BY_FIELD = "updated_by"
        const val DEFAULT_SETTINGS_ID = "default"
        const val UPDATE_RESULT_UPDATED = "1"
        const val UPDATE_RESULT_CONFLICT = "0"

        val SETTINGS_FIELDS = listOf(
            ENABLED_FIELD,
            AUTHENTICATED_FIELD,
            ANONYMOUS_FIELD,
            EXCLUDED_PATHS_FIELD,
            SETTINGS_ID_FIELD,
            VERSION_FIELD,
            UPDATED_AT_FIELD,
            UPDATED_BY_FIELD,
        )

        val updateSettingsScript = DefaultRedisScript(
            """
                local exists = redis.call('EXISTS', KEYS[1])
                local rawVersion = redis.call('HGET', KEYS[1], 'version')
                local actual
                local settingsId
                if exists == 1 then
                    actual = tonumber(rawVersion)
                    settingsId = redis.call('HGET', KEYS[1], 'settings_id')
                    if not actual or actual < 1 or not settingsId then return 'E' end
                else
                    actual = 0
                    settingsId = ARGV[6]
                end
                if actual ~= tonumber(ARGV[1]) then
                    return '0|' .. actual
                end

                local nextVersion = actual + 1
                redis.call('HSET', KEYS[1],
                    'enabled', ARGV[2],
                    'authenticated_requests_per_minute', ARGV[3],
                    'anonymous_requests_per_minute', ARGV[4],
                    'excluded_paths', ARGV[5],
                    'settings_id', settingsId,
                    'version', nextVersion,
                    'updated_at', ARGV[7],
                    'updated_by', ARGV[8])
                return '1|' .. nextVersion .. '|' .. settingsId
            """.trimIndent(),
            String::class.java,
        )
    }
}
