package top.foxball.shopmall.authentication

import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import top.foxball.shopmall.config.JwtProperties
import top.foxball.shopmall.handler.RefreshTokenExpiredException
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.shared.RefreshTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoginTokenAuthenticationImplTest {

    @Test
    fun `expired refresh token raises dedicated exception before session lookup`() {
        val jwtService = JwtService("test-secret-with-at-least-thirty-two-bytes")
        val userRepository = mock(UserRepository::class.java)
        val store = mock(RefreshTokenStore::class.java)
        val authentication = LoginTokenAuthenticationImpl(
            jwtService = jwtService,
            jwtProperties = JwtProperties(),
            userRepository = userRepository,
            store = store,
        )
        val refreshToken = jwtService.issue(
            userId = 42,
            type = TokenType.REFRESH,
            ttlSeconds = 1,
            familyId = "11111111-1111-1111-1111-111111111111",
        ).token
        Thread.sleep(1_100)

        val exception = assertFailsWith<RefreshTokenExpiredException> {
            authentication.refresh(refreshToken, "test-agent")
        }

        assertEquals("Refresh Token 已过期", exception.message)
        verifyNoInteractions(userRepository, store)
    }
}
