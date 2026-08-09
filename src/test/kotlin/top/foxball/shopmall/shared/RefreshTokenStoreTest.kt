package top.foxball.shopmall.shared

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import top.foxball.shopmall.config.JwtProperties
import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshTokenStoreTest {
    private val redis = mock(StringRedisTemplate::class.java)
    private val properties = JwtProperties(
        refresh = JwtProperties.Refresh(graceSeconds = 45),
    )
    private val store = RefreshTokenStore(redis, properties)

    @Test
    fun `rotation passes each Lua argument as a string`() {
        `when`(
            redis.execute<List<*>>(
                any(),
                eq(listOf("refresh:token:old-jti")),
                eq("1786252324"),
                eq("45"),
                eq("new-jti"),
            ),
        ).thenReturn(listOf("rotate", "120000"))

        val result = store.decideRotation(
            jti = "old-jti",
            newJti = "new-jti",
            nowEpoch = 1_786_252_324,
        )

        assertEquals(RefreshTokenStore.RotationVerdict.Rotate(120_000), result)
    }
}
