package top.foxball.shopmall.authentication

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import top.foxball.shopmall.config.DevTokenManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtAuthenticationFilterTest {
    private val jwtService = JwtService("test-secret-with-at-least-thirty-two-bytes")
    private val devTokenManager = mock(DevTokenManager::class.java)
    private val filter = JwtAuthenticationFilter(jwtService, devTokenManager)

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `async dispatch restores administrator authentication from bearer token`() {
        `when`(devTokenManager.fixedTokenUserId(any(JwtService.Claims::class.java))).thenReturn(null)
        val accessToken = jwtService.issue(
            userId = 42,
            type = TokenType.ACCESS,
            ttlSeconds = 60,
            role = "ADMIN",
        ).token
        val request = MockHttpServletRequest("GET", "/admin/api/logs/live").apply {
            dispatcherType = DispatcherType.ASYNC
            addHeader("Authorization", "Bearer $accessToken")
        }
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        val authentication = assertNotNull(SecurityContextHolder.getContext().authentication)
        assertEquals(42L, authentication.principal)
        assertTrue(authentication.authorities.any { it.authority == "ROLE_ADMIN" })
        verify(chain).doFilter(request, response)
    }
}
