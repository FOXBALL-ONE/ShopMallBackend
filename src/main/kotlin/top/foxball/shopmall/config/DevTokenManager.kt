package top.foxball.shopmall.config

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.authentication.JwtService
import top.foxball.shopmall.authentication.TokenType
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.UserRepository

/**
 * 开发固定令牌生命周期：启动时确保默认管理员存在，并把固定 JWT 绑定到该管理员的数据库 userId。
 *
 * - 仅当 [DevTokenProperties.enabled] 为 true 时执行；生产环境保持关闭即无副作用。
 * - 默认管理员按 [DefaultAdminProperties] 落库：首次创建，之后每次启动把密码/角色/启用状态/邮箱同步到配置。
 * - 解析出的管理员 userId 存入 [resolvedUserId]，供 [top.foxball.shopmall.authentication.JwtAuthenticationFilter]
 *   在请求期识别固定令牌（jti + sub 双匹配）。
 *
 * 这套机制取代了原先静态配置 userId 的做法——避免依赖某个固定自增 ID（库里已有用户时未必是 1）。
 */
@Component
class DevTokenManager(
    private val jwtService: JwtService,
    private val properties: DevTokenProperties,
    private val adminProperties: DefaultAdminProperties,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    /** 启动期解析出的默认管理员 userId；null 表示尚未 provision（此时固定令牌不生效）。 */
    @Volatile
    private var resolvedUserId: Long? = null

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun provision() {
        if (!properties.enabled) return
        val admin = ensureDefaultAdmin()
        val userId = requireNotNull(admin.id) { "默认管理员保存后未生成 id" }
        resolvedUserId = userId
        val issued = jwtService.issue(
            userId, TokenType.ACCESS, properties.ttlSeconds,
            role = Role.ADMIN.name, jti = properties.jti,
        )
        println("⚠ DEV 固定 JWT 已启用，绑定管理员 '${admin.username}' (id=$userId)。切勿用于生产！")
        println("   Authorization: Bearer ${issued.token}")
    }

    /**
     * 若 [claims] 是当前绑定的固定令牌，返回其管理员 userId；否则 null。
     * 过滤器据此决定是否走 dev 旁路。固定令牌现在带 typ=access + role=ADMIN，
     * 过滤器用 verify(token, ACCESS) 校验，故这里必须断言类型为 ACCESS 再放行；
     * jti 与 sub 都必须与启动期一致，避免误命中。
     */
    fun fixedTokenUserId(claims: JwtService.Claims?): Long? {
        val target = resolvedUserId ?: return null
        if (claims == null || !properties.enabled) return null
        if (claims.type != TokenType.ACCESS) return null      // 固定令牌必须是 access 语义
        if (claims.jti != properties.jti) return null
        return if (claims.userId == target) target else null
    }

    /**
     * 确保默认管理员存在：不存在则创建；已存在则把密码/角色/启用状态/邮箱对齐到配置。
     * 返回持久化后的管理员实体（含 id）。
     */
    private fun ensureDefaultAdmin(): User {
        val existing = userRepository.findByUsername(adminProperties.username)
        if (existing == null) {
            val created = userRepository.save(
                User(
                    username = adminProperties.username,
                    email = adminProperties.email,
                    password = requireNotNull(passwordEncoder.encode(adminProperties.password)) { "密码编码失败" },
                    role = Role.ADMIN,
                    enabled = true,
                    emailVerified = true,
                )
            )
            println("• 已创建默认管理员 '${created.username}' (id=${created.id})")
            return created
        }

        // 已存在：把密码/角色/启用状态/邮箱同步到配置，保证 dev 可预期登录与管理员身份
        var changed = false
        if (!passwordEncoder.matches(adminProperties.password, existing.password)) {
            existing.password = requireNotNull(passwordEncoder.encode(adminProperties.password)) { "密码编码失败" }
            changed = true
        }
        if (existing.role != Role.ADMIN) {
            existing.role = Role.ADMIN
            println("⚠ 用户 '${existing.username}' 角色非 ADMIN，已提升为管理员")
            changed = true
        }
        if (!existing.enabled) {
            existing.enabled = true
            changed = true
        }
        if (existing.email != adminProperties.email) {
            existing.email = adminProperties.email
            changed = true
        }
        return if (changed) {
            userRepository.save(existing).also {
                println("• 默认管理员 '${it.username}' (id=${it.id}) 已按配置同步")
            }
        } else {
            println("• 默认管理员 '${existing.username}' (id=${existing.id}) 已是最新")
            existing
        }
    }
}
