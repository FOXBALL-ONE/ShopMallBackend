package top.foxball.shopmall.authentication

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 认证过滤器：从 `Authorization: Bearer <jwt>` 取令牌，
 * 经 [JwtService] 验签（签名 + 过期）后，再用 [LoginTokenAuthentication] 校验白名单（撤销/登出）与 UA 绑定，
 * 通过则把 `userId` 写入 [SecurityContextHolder]。失败不设置认证，交由 `authorizeHttpRequests` 判定 401。
 */
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val loginTokenAuthentication: LoginTokenAuthentication,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)
        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            // JWT 保证完整性，Redis 白名单支持主动撤销：两者都通过才认定会话有效
            val claims = jwtService.verify(token)
            if (claims != null &&
                loginTokenAuthentication.isValid(claims.userId, claims.jti, userAgent(request))
            ) {
                val authentication = UsernamePasswordAuthenticationToken(claims.userId, null, emptyList())
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(AUTHORIZATION_HEADER) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        // 按前缀长度截取，兼容大小写不一的 scheme
        return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    private fun userAgent(request: HttpServletRequest): String =
        request.getHeader("User-Agent").orEmpty()

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
