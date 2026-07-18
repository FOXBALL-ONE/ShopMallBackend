package top.foxball.shopmall.authentication

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import top.foxball.shopmall.config.DevTokenManager

/**
 * JWT 认证过滤器：从 `Authorization: Bearer <jwt>` 取令牌，
 * 经 [JwtService] 验签（签名 + 过期）后，再用 [LoginTokenAuthentication] 校验白名单（撤销/登出）与 UA 绑定，
 * 通过则把 `userId` 写入 [SecurityContextHolder]。失败不设置认证，交由 `authorizeHttpRequests` 判定 401。
 *
 * 开发旁路：[devTokenManager] 识别到固定令牌（绑定启动期落库的默认管理员）时，跳过 Redis 白名单与
 * UA 绑定直接放行——令牌仍需通过 HS256 验签，故泄漏密钥才会被伪造。生产环境务必保持关闭。
 */
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val loginTokenAuthentication: LoginTokenAuthentication,
    private val devTokenManager: DevTokenManager,
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
            val devUserId = devTokenManager.fixedTokenUserId(claims)
            when {
                devUserId != null -> authenticate(devUserId)
                claims != null &&
                    loginTokenAuthentication.isValid(claims.userId, claims.jti, userAgent(request)) ->
                    authenticate(claims.userId)
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(userId: Long) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
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
