package top.foxball.shopmall.authentication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import top.foxball.shopmall.entity.jdbc.Role

/** [SecurityContextUser] 从 SecurityContext 读取预设 userId/role 的行为测试。 */
class SecurityContextUserTest {

    private val securityContextUser = SecurityContextUser()

    @Test
    fun `未认证时 userId 与 role 为 null`() {
        SecurityContextHolder.clearContext()
        assertNull(securityContextUser.userId)
        assertNull(securityContextUser.role)
        assertFalse(securityContextUser.isAuthenticated)
        assertFalse(securityContextUser.isAdmin())
    }

    @Test
    fun `ADMIN 令牌可读出 userId 与角色`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(42L, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        try {
            assertEquals(42L, securityContextUser.userId)
            assertEquals(Role.ADMIN, securityContextUser.role)
            assertTrue(securityContextUser.isAuthenticated)
            assertTrue(securityContextUser.isAdmin())
            assertEquals(42L, securityContextUser.requireUserId())
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `CUSTOMER 令牌可读出角色且非管理员`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(7L, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
        try {
            assertEquals(7L, securityContextUser.userId)
            assertEquals(Role.CUSTOMER, securityContextUser.role)
            assertFalse(securityContextUser.isAdmin())
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
