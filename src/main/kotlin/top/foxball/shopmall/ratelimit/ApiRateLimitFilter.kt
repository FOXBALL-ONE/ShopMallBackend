package top.foxball.shopmall.ratelimit

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/** Global API sliding-window limiter, registered exclusively in Spring Security's filter chain. */
class ApiRateLimitFilter(
    private val settingsService: RateLimitSettingsService,
    private val rateLimitService: ApiRateLimitService,
    private val clientIpResolver: ClientIpResolver,
    private val objectMapper: ObjectMapper,
    private val metrics: RateLimitMetrics,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilterAsyncDispatch(): Boolean = true

    override fun shouldNotFilterErrorDispatch(): Boolean = true

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (request.dispatcherType != DispatcherType.REQUEST) return true
        if (request.method.equals(HttpMethod.OPTIONS.name(), ignoreCase = true)) {
            metrics.exclusion("fixed")
            return true
        }
        val path = request.requestURI.removePrefix(request.contextPath)
        if (path == "/actuator/health" || path == "/actuator/info" || path == "/error") {
            metrics.exclusion("fixed")
            return true
        }
        if (!isManagedPath(path)) return true
        val excludedWebhook = request.method.equals(HttpMethod.POST.name(), ignoreCase = true) &&
            (path == "/webhook" || path == "/api/logistics/webhook" || path.startsWith("/api/logistics/webhook/"))
        if (excludedWebhook) metrics.exclusion("fixed")
        return excludedWebhook
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val settings = try {
            settingsService.getSettings()
        } catch (exception: RuntimeException) {
            failClosed(response, exception)
            return
        }
        if (!settings.enabled) {
            filterChain.doFilter(request, response)
            return
        }
        val dynamicallyExcluded = try {
            settingsService.matchesExcludedPath(settings, request)
        } catch (exception: RuntimeException) {
            failClosed(response, exception)
            return
        }
        if (dynamicallyExcluded) {
            metrics.exclusion("dynamic")
            filterChain.doFilter(request, response)
            return
        }

        val decision = try {
            val principal = SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated }
                ?.principal as? Long
            val identity = if (principal != null) RateLimitIdentityType.AUTHENTICATED else RateLimitIdentityType.ANONYMOUS
            val subject = principal?.toString() ?: clientIpResolver.resolve(request)
            val limit = if (identity == RateLimitIdentityType.AUTHENTICATED) {
                settings.authenticatedRequestsPerMinute
            } else {
                settings.anonymousRequestsPerMinute
            }
            rateLimitService.decide(identity, subject, limit)
        } catch (exception: RuntimeException) {
            failClosed(response, exception)
            return
        }
        if (decision.allowed) {
            response.setHeader(HEADER_LIMIT, decision.limit.toString())
            response.setHeader(HEADER_REMAINING, decision.remaining.toString())
            filterChain.doFilter(request, response)
            return
        }
        writeTooManyRequests(response, decision)
    }

    private fun writeTooManyRequests(response: HttpServletResponse, decision: RateLimitDecision) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = "application/json;charset=UTF-8"
        response.setHeader("Retry-After", decision.retryAfterSeconds.toString())
        response.setHeader(HEADER_LIMIT, decision.limit.toString())
        response.setHeader(HEADER_REMAINING, "0")
        objectMapper.writeValue(
            response.writer,
            mapOf(
                "status" to HttpStatus.TOO_MANY_REQUESTS.value(),
                "message" to "请求过于频繁，请在 ${decision.retryAfterSeconds} 秒后重试",
                "data" to emptyMap<String, Any?>(),
            ),
        )
    }

    private fun writeServiceUnavailable(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
        response.contentType = "application/json;charset=UTF-8"
        response.setHeader("Retry-After", "1")
        objectMapper.writeValue(
            response.writer,
            mapOf(
                "status" to HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "message" to "系统繁忙，请稍后重试",
                "data" to emptyMap<String, Any?>(),
            ),
        )
    }

    private fun failClosed(response: HttpServletResponse, exception: RuntimeException) {
        metrics.error()
        if (exception is RateLimitUnavailableException) {
            log.error("Global API rate limiter failed closed: {}", exception.message)
        } else {
            log.error("Unexpected global API rate limiter failure", exception)
        }
        writeServiceUnavailable(response)
    }

    private fun isManagedPath(path: String): Boolean =
        path == "/webhook" || hasManagedNamespace(path, "/api") || hasManagedNamespace(path, "/admin/api")

    private fun hasManagedNamespace(path: String, namespace: String): Boolean {
        if (!path.startsWith(namespace)) return false
        if (path.length == namespace.length) return true
        val boundary = path[namespace.length]
        return boundary == '/' || boundary == ';' || boundary == '%' || boundary == '\\' ||
            boundary == '?' || boundary == '#' || boundary.isWhitespace() || boundary.isISOControl()
    }

    private companion object {
        const val HEADER_LIMIT = "X-RateLimit-Limit"
        const val HEADER_REMAINING = "X-RateLimit-Remaining"
    }
}
