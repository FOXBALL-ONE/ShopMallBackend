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

    @Test
    fun `指定 jti 签发的固定令牌原样回读`() {
        // dev 固定令牌场景：用已知的 jti + userId 签发，verify 后声明必须完全一致
        val fixedJti = "00000000-0000-0000-0000-000000000000"
        val issued = jwtService.issue(userId = 7, jti = fixedJti, ttlSeconds = 60)

        assertEquals(fixedJti, issued.jti)
        val claims = assertNotNull(jwtService.verify(issued.token))
        assertEquals(7, claims.userId)
        assertEquals(fixedJti, claims.jti)
    }

    @Test
    fun `指定 jti 签发的令牌可重复生成且识别一致`() {
        // 固定 jti + userId + ttl 在同一秒内是确定性的（iat 截断到秒）；无论字符串是否相同，
        // 关键是都可通过验签并回读出同一 jti/userId —— 过滤器据此识别同一张固定令牌。
        val fixedJti = "11111111-2222-3333-4444-555555555555"
        val first = jwtService.issue(userId = 1, jti = fixedJti, ttlSeconds = 60)
        val second = jwtService.issue(userId = 1, jti = fixedJti, ttlSeconds = 60)

        assertEquals(first.jti, second.jti)
        val firstClaims = assertNotNull(jwtService.verify(first.token))
        val secondClaims = assertNotNull(jwtService.verify(second.token))
        assertEquals(fixedJti, firstClaims.jti)
        assertEquals(fixedJti, secondClaims.jti)
        assertEquals(1, firstClaims.userId)
        assertEquals(1, secondClaims.userId)
    }

    @Test
    fun `拒绝非法 UUID 的 jti`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService.issue(userId = 1, jti = "not-a-uuid", ttlSeconds = 60)
        }
    }
}
