package top.foxball.shopmall.config

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.foxball.shopmall.authentication.JwtService
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.UserRepository

/** 默认管理员的幂等落库，以及固定令牌按"启动期解析出的管理员 id"绑定。 */
class DevTokenManagerTest {

    private val jwtService = JwtService("test-secret-with-at-least-thirty-two-bytes")
    private val properties = DevTokenProperties(
        enabled = true,
        jti = "00000000-0000-0000-0000-000000000000",
        ttlSeconds = 60L,
    )
    private val adminProperties = DefaultAdminProperties(username = "admin", password = "admin", email = "admin")

    /** 签发一张指定 userId/jti 的令牌字符串，供 verify 回读为 claims。 */
    private fun tokenFor(userId: Long, jti: String): String = jwtService.issue(userId, jti, 60).token

    @Test
    fun `provision 创建默认管理员并把令牌绑定到其解析后的 id`() {
        val userRepository = mock(UserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        `when`(userRepository.findByUsername("admin")).thenReturn(null)
        `when`(passwordEncoder.encode("admin")).thenReturn("hashed")
        // 模拟数据库分配自增 id=42（非配置硬编码，证明绑定走解析值）
        `when`(userRepository.save(any(User::class.java)))
            .thenReturn(User(id = 42, username = "admin", role = Role.ADMIN))
        val manager = DevTokenManager(jwtService, properties, adminProperties, userRepository, passwordEncoder)

        manager.provision()

        // sub=42 + 配置 jti 的令牌应被识别并返回该管理员 id
        val claims = assertNotNull(jwtService.verify(tokenFor(42, properties.jti)))
        assertEquals(42, manager.fixedTokenUserId(claims))
        // jti 或 sub 任一不匹配都不应放行（避免误命中）
        val wrongJti = assertNotNull(jwtService.verify(tokenFor(42, "11111111-1111-1111-1111-111111111111")))
        assertNull(manager.fixedTokenUserId(wrongJti))
        val wrongSub = assertNotNull(jwtService.verify(tokenFor(999, properties.jti)))
        assertNull(manager.fixedTokenUserId(wrongSub))
    }

    @Test
    fun `provision 管理员已存在且密码未变时不重复保存`() {
        val userRepository = mock(UserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        `when`(userRepository.findByUsername("admin")).thenReturn(
            User(
                id = 7,
                username = "admin",
                email = "admin",
                password = "hashed",
                role = Role.ADMIN,
                enabled = true,
                emailVerified = true,
            ),
        )
        `when`(passwordEncoder.matches("admin", "hashed")).thenReturn(true)
        val manager = DevTokenManager(jwtService, properties, adminProperties, userRepository, passwordEncoder)

        manager.provision()

        verify(userRepository, never()).save(any(User::class.java))
        val claims = assertNotNull(jwtService.verify(tokenFor(7, properties.jti)))
        assertEquals(7, manager.fixedTokenUserId(claims))
    }
}
