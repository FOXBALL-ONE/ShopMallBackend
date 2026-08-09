package top.foxball.shopmall.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Prevents the live-tail request's own framework logs from waking that same long poll. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LiveLogRequestMdcFilter : OncePerRequestFilter() {
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun shouldNotFilterErrorDispatch(): Boolean = false

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return !request.method.equals(HttpMethod.GET.name(), ignoreCase = true) || path != LIVE_LOG_PATH
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val previous = MDC.get(ShopMallLogAppender.LIVE_TAIL_EXCLUDED_MDC_KEY)
        MDC.put(ShopMallLogAppender.LIVE_TAIL_EXCLUDED_MDC_KEY, "true")
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (previous == null) {
                MDC.remove(ShopMallLogAppender.LIVE_TAIL_EXCLUDED_MDC_KEY)
            } else {
                MDC.put(ShopMallLogAppender.LIVE_TAIL_EXCLUDED_MDC_KEY, previous)
            }
        }
    }

    private companion object {
        const val LIVE_LOG_PATH = "/admin/api/logs/live"
    }
}
