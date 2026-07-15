package top.foxball.shopmall.authentication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** JWT 签发唯一性、声明回读和有效期参数校验测试。 */
class JwtServiceTest {

    private val jwtService = JwtService("test-secret-with-at-least-thirty-two-bytes")

    @Test
    fun `每次签发使用不同 jti 并能在校验后取得`() {
        val first = jwtService.issue(userId = 42, ttlSeconds = 60)
        val second = jwtService.issue(userId = 42, ttlSeconds = 60)

        assertNotEquals(first.jti, second.jti)
        assertNotEquals(first.token, second.token)
        val claims = assertNotNull(jwtService.verify(first.token))
        assertEquals(42, claims.userId)
        assertEquals(first.jti, claims.jti)
    }

    @Test
    fun `拒绝非正数有效期`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService.issue(userId = 42, ttlSeconds = 0)
        }
    }
}
