package top.foxball.shopmall.authentication

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import top.foxball.shopmall.config.DevTokenManager

/**
 * JWT 认证过滤器：双 Token 模型下，受保护请求仅验 **访问令牌**（无状态、不查 Redis）。
 *
 * 从 `Authorization: Bearer <jwt>` 取令牌，经 [JwtService.verify] 校验签名 + 过期 + **类型必须为 ACCESS**
 * （refresh 当 Bearer 直接被拒，类型隔离的执行点），通过则把 `userId` 与 `ROLE_<role>` 写入
 * [SecurityContextHolder]。失败不设置认证，交由 `authorizeHttpRequests` 判定 401。
 *
 * 去掉了旧模型的每请求 Redis 白名单查询（`LoginTokenAuthentication.isValid`）——令牌完整性已由 HS256
 * 保证，撤销靠刷新令牌侧（登出/改密删 refresh，access 在 ≤ [top.foxball.shopmall.config.JwtProperties.Access.ttlSeconds] 内自然过期）。
 *
 * 开发旁路：[devTokenManager] 识别到固定令牌（绑定启动期落库的默认管理员）时直接放行——令牌仍需通过
 * HS256 验签且 `typ=access`，故泄漏密钥才会被伪造。生产环境务必保持关闭。
 *
 * 设计详见 `docs/dual-token-auth-design.md` §4.1 / §5.2。
 */
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val devTokenManager: DevTokenManager,
) : OncePerRequestFilter() {

    // suspend 控制器完成时会触发 ASYNC 二次派发；无状态会话需要重新从 Bearer token 恢复认证。
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)
        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            // 仅接受 typ=access；refresh 当 Bearer 使用直接被拒（verify 返回 null）
            val claims = jwtService.verify(token, TokenType.ACCESS)
            val devUserId = devTokenManager.fixedTokenUserId(claims)
            when {
                // dev 旁路优先：固定令牌仍是 access 语义（已校验 typ=access + role=ADMIN）
                devUserId != null -> authenticate(devUserId, "ROLE_ADMIN")
                // 正常分支：role 由 verify 保证为已知角色（非已知 → null），映射为 Spring Security authority
                claims != null -> authenticate(claims.userId, "ROLE_${claims.role ?: "CUSTOMER"}")
            }
        }
        filterChain.doFilter(request, response)
    }

    /** 写入 SecurityContext：[userId] 作 principal，[roles] 映射为 GrantedAuthority（`hasRole` 去前缀匹配）。 */
    private fun authenticate(userId: Long, vararg roles: String) {
        val authorities = roles.map { SimpleGrantedAuthority(it) }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, authorities)
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(AUTHORIZATION_HEADER) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        // 按前缀长度截取，兼容大小写不一的 scheme
        return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
