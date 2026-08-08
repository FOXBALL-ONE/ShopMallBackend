package top.foxball.shopmall.authentication

import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.handler.UnauthorizedException

/**
 * 从 [SecurityContextHolder] 读取当前请求的预设用户信息（userId + role）。
 *
 * 双 Token 模型下，[JwtAuthenticationFilter] 验证 access 令牌后，把令牌内嵌的 `userId` 作为 principal、
 * `role` claim 映射为 `GrantedAuthority`（`ROLE_<role>`）写入 SecurityContext。故「权限、ID 等预设值」
 * 在请求期间已就绪，本类无状态地从中读取，**无需查 DB**——角色取自令牌签发时冻结的 claim
 * （降级最长滞后一个 access TTL，敏感操作仍可在 service 层二次读 DB，见设计 §十）。
 *
 * 控制器仍用 `@AuthenticationPrincipal userId: Long` 取 ID（设计 §5.2 约定不变）；需要角色/权限等
 * 预设信息时注入本类调用，避免散落的 `SecurityContextHolder` 直读与 authority 字符串解析。
 *
 * 设计详见 `docs/dual-token-auth-design.md` §4.1 / §5.2。
 */
@Component
class SecurityContextUser {

    /** 当前认证主体；无认证（匿名/未登录）返回 null。 */
    private val authentication: Authentication?
        get() = SecurityContextHolder.getContext().authentication

    /** 当前登录用户 ID；未认证返回 null。 */
    val userId: Long?
        get() = (authentication?.principal as? Long)

    /** 当前登录用户角色（由 `ROLE_<role>` authority 反解）；未认证返回 null。 */
    val role: Role?
        get() = roleAuthority()?.let { Role.fromAuthority(it) }

    /** 当前是否已认证（非匿名）。 */
    val isAuthenticated: Boolean
        get() = authentication?.isAuthenticated == true && authentication?.principal is Long

    /** 当前是否为管理员；未认证或非 ADMIN 返回 false。 */
    fun isAdmin(): Boolean = role == Role.ADMIN

    /** 要求已登录，否则以统一 401 语义拒绝请求；返回当前 userId。 */
    fun requireUserId(): Long = userId ?: throw UnauthorizedException()

    private fun roleAuthority(): String? =
        authentication?.authorities?.firstNotNullOfOrNull { it.toRoleName() }

    private companion object {
        private const val ROLE_PREFIX = "ROLE_"

        /** 把 `ROLE_ADMIN` 形态的 authority 归一为角色名 `ADMIN`；非 `ROLE_` 前缀的 authority 返回 null。 */
        fun GrantedAuthority.toRoleName(): String? =
            authority?.takeIf { it.startsWith(ROLE_PREFIX) }?.removePrefix(ROLE_PREFIX)
    }
}
