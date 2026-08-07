package top.foxball.shopmall.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/** Global API rate-limit settings bound from `shopmall.rate-limit.*`. */
@ConfigurationProperties(prefix = "shopmall.rate-limit")
data class RateLimitProperties(
    /** Default runtime state used until an administrator creates a Redis settings hash. */
    val enabled: Boolean = true,
    /** Deployment-level guard. When false, the filter is not registered at all. */
    val filterEnabled: Boolean = true,
    val windowSeconds: Long = 60,
    val defaultAuthenticatedRequestsPerMinute: Int = 10,
    val defaultAnonymousRequestsPerMinute: Int = 5,
    val identityHashSecret: String = "",
    val defaultExcludedPaths: List<String> = emptyList(),
    val trustedProxyCidrs: List<String> = emptyList(),
) {
    init {
        require(windowSeconds == WINDOW_SECONDS) {
            "shopmall.rate-limit.window-seconds must be $WINDOW_SECONDS"
        }
        require(defaultAuthenticatedRequestsPerMinute in MIN_REQUESTS_PER_MINUTE..MAX_REQUESTS_PER_MINUTE) {
            "shopmall.rate-limit.default-authenticated-requests-per-minute must be between " +
                "$MIN_REQUESTS_PER_MINUTE and $MAX_REQUESTS_PER_MINUTE"
        }
        require(defaultAnonymousRequestsPerMinute in MIN_REQUESTS_PER_MINUTE..MAX_REQUESTS_PER_MINUTE) {
            "shopmall.rate-limit.default-anonymous-requests-per-minute must be between " +
                "$MIN_REQUESTS_PER_MINUTE and $MAX_REQUESTS_PER_MINUTE"
        }
        require(!filterEnabled || identityHashSecret.length >= MIN_IDENTITY_HASH_SECRET_LENGTH) {
            "shopmall.rate-limit.identity-hash-secret must be at least " +
                "$MIN_IDENTITY_HASH_SECRET_LENGTH characters"
        }
        RateLimitPathRules.normalize(defaultExcludedPaths)
        trustedProxyCidrs.forEach(TrustedProxyCidr::parse)
    }

    companion object {
        const val WINDOW_SECONDS = 60L
        const val MIN_REQUESTS_PER_MINUTE = 1
        const val MAX_REQUESTS_PER_MINUTE = 1_000
        const val MIN_IDENTITY_HASH_SECRET_LENGTH = 32
    }
}
