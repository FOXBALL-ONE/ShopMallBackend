package top.foxball.shopmall.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.authentication.JwtService
import top.foxball.shopmall.authentication.TokenType

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminApiSecurityIntegrationTest.AdminApiSecurityProbe::class)
class AdminApiSecurityIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
) {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `anonymous request to admin api is unauthorized`() {
        mockMvc.perform(get("/admin/api/security-probe"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value(401))
    }

    @Test
    fun `customer access token cannot access admin api`() {
        mockMvc.perform(
            get("/admin/api/security-probe")
                .header("Authorization", bearerToken(userId = 41L, role = "CUSTOMER")),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.status").value(403))
    }

    @Test
    fun `admin access token authenticates on admin api`() {
        mockMvc.perform(
            get("/admin/api/security-probe")
                .header("Authorization", bearerToken(userId = 42L, role = "ADMIN")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.user_id").value(42L))
    }

    private fun bearerToken(userId: Long, role: String): String =
        "Bearer ${jwtService.issue(userId, TokenType.ACCESS, ttlSeconds = 60, role = role).token}"

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    class AdminApiSecurityProbe {
        @GetMapping("/admin/api/security-probe")
        fun probe(@AuthenticationPrincipal userId: Long): Map<String, Long> = mapOf("user_id" to userId)
    }
}
