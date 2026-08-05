package top.foxball.shopmall.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** 在 Multipart 解析前拦截明显超大的工单附件消息请求。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SupportTicketUploadSizeFilter(
    private val properties: SupportTicketProperties,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return request.method != HttpMethod.POST.name() || !MESSAGE_PATH.matches(path)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val contentLength = request.contentLengthLong
        if (contentLength > properties.maxMessageRequestBytes) {
            response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                """{"status":413,"message":"工单消息附件请求不能超过 ${properties.maxMessageRequestBytes} 字节","data":{}}""",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        val MESSAGE_PATH = Regex("^/(?:admin/)?api/support-tickets/[^/]+/messages/?$")
    }
}
