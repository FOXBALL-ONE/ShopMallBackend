package top.foxball.shopmall.ratelimit

import top.foxball.shopmall.handler.ParamErrorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimitPathRulesTest {
    @Test
    fun `normalizes, deduplicates, and sorts valid exact and subtree paths`() {
        assertEquals(
            listOf("/api/catalog/**", "/api/files/public"),
            RateLimitPathRules.normalize(
                listOf("/api/files/public", "/api/catalog/**", "/api/files/public"),
            ),
        )
    }

    @Test
    fun `rejects broad and sensitive dynamic exclusions`() {
        listOf(
            "/api/**",
            "/api/auth",
            "/api/auth/login",
            "/api/logistics/webhook/**",
            "/admin/api/users",
            "/api/catalog/*",
        ).forEach { path ->
            assertFailsWith<ParamErrorException>("Expected $path to be rejected") {
                RateLimitPathRules.normalize(listOf(path))
            }
        }
    }

    @Test
    fun `rejects noncanonical paths and invalid persisted representation`() {
        listOf(
            "/api/catalog//public",
            "/api/catalog/%2fpublic",
            "/api/catalog/../public",
            "/api/catalog/{id}",
            "/api/catalog/ ",
            "/api/catalog/",
        ).forEach { path ->
            assertFailsWith<ParamErrorException>("Expected $path to be rejected") {
                RateLimitPathRules.normalize(listOf(path))
            }
        }

        assertFailsWith<ParamErrorException> {
            RateLimitPathRules.parseStored("/api/files/public\n/api/catalog/**")
        }
    }

    @Test
    fun `recognizes only canonical request paths for matching`() {
        assertTrue(RateLimitPathRules.isCanonicalRequestPath("/api/catalog/items"))
        assertFalse(RateLimitPathRules.isCanonicalRequestPath("/api/catalog//items"))
        assertFalse(RateLimitPathRules.isCanonicalRequestPath("/api/catalog/%2fitems"))
        assertFalse(RateLimitPathRules.isCanonicalRequestPath("/api/catalog;v=1/items"))
    }
}
