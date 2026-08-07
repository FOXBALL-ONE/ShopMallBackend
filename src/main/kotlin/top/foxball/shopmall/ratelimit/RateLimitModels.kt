package top.foxball.shopmall.ratelimit

import java.time.LocalDateTime

enum class RateLimitSettingsSource {
    DEFAULT,
    REDIS,
}

enum class RateLimitIdentityType {
    AUTHENTICATED,
    ANONYMOUS,
}

data class RateLimitSettings(
    val windowSeconds: Long,
    val authenticatedRequestsPerMinute: Int,
    val anonymousRequestsPerMinute: Int,
    val excludedPaths: List<String>,
    val version: Long,
    val source: RateLimitSettingsSource,
    val updatedAt: LocalDateTime?,
    val updatedBy: Long?,
    internal val settingsId: String,
    internal val excludedPathsRaw: String,
    /** Whether the runtime policy currently enforces quotas for managed requests. */
    val enabled: Boolean = true,
)

data class UpdateRateLimitSettingsCommand(
    val authenticatedRequestsPerMinute: Int,
    val anonymousRequestsPerMinute: Int,
    val excludedPaths: List<String>,
    val expectedVersion: Long,
    val enabled: Boolean = true,
)

sealed interface RateLimitSettingsUpdateResult {
    data class Updated(val settings: RateLimitSettings) : RateLimitSettingsUpdateResult

    data class Conflict(val actualVersion: Long) : RateLimitSettingsUpdateResult
}

data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val retryAfterSeconds: Long,
)

class RateLimitUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
