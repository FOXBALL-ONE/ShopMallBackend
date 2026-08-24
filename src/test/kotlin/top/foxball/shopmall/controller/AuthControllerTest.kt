package top.foxball.shopmall.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.authentication.RefreshCookieService
import top.foxball.shopmall.config.JwtProperties
import top.foxball.shopmall.service.AuthService
import top.foxball.shopmall.service.MailService
import top.foxball.shopmall.service.PasswordResetService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder

class AuthControllerTest {
    private lateinit var authService: AuthService
    private lateinit var refreshCookieService: RefreshCookieService
    private lateinit var passwordResetService: PasswordResetService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        authService = mock(AuthService::class.java)
        passwordResetService = mock(PasswordResetService::class.java)
        refreshCookieService = RefreshCookieService(JwtProperties())
        mockMvc = MockMvcBuilders.standaloneSetup(
            AuthController(
                authService = authService,
                mailService = mock(MailService::class.java),
                passwordResetService = passwordResetService,
                userService = mock(UserService::class.java),
                loginTokenAuthentication = mock(LoginTokenAuthentication::class.java),
                refreshCookieService = refreshCookieService,
                jwtProperties = JwtProperties(),
                builder = ResponseBuilder(),
            ),
        ).build()
    }

    @Test
    fun `login returns ecommerce account data without legacy frp fields`() {
        val result = LoginTokenAuthentication.LoginResult(
            accessToken = "access-token",
            expiresIn = 1800,
            userId = 7,
            userInfo = LoginTokenAuthentication.LoginResult.UserInfo(
                username = "customer",
                email = "customer@example.com",
                firstName = "Alex",
                lastName = "Morgan",
                avatar = "https://cdn.example.com/avatar.jpg",
                role = "CUSTOMER",
                locale = "en-US",
                currency = "USD",
            ),
            refreshJwt = "refresh-token",
        )
        `when`(
            authService.login("customer@example.com", "password123", "Browser", "127.0.0.1"),
        ).thenReturn(result)

        mockMvc.perform(
            post("/api/auth/login")
                .header("User-Agent", "Browser")
                .param("identifier", "customer@example.com")
                .param("password", "password123"),
        )
            .andExpect(status().isOk)
            .andExpect(header().exists("Set-Cookie"))
            .andExpect(jsonPath("$.data.access_token").value("access-token"))
            .andExpect(jsonPath("$.data.expires_in").value(1800))
            .andExpect(jsonPath("$.data.user_id").value(7))
            .andExpect(jsonPath("$.data.user_info.username").value("customer"))
            .andExpect(jsonPath("$.data.user_info.first_name").value("Alex"))
            .andExpect(jsonPath("$.data.user_info.last_name").value("Morgan"))
            .andExpect(jsonPath("$.data.user_info.avatar").value("https://cdn.example.com/avatar.jpg"))
            .andExpect(jsonPath("$.data.user_info.role").value("CUSTOMER"))
            .andExpect(jsonPath("$.data.user_info.locale").value("en-US"))
            .andExpect(jsonPath("$.data.user_info.currency").value("USD"))
            .andExpect(jsonPath("$.data.frp_token").doesNotExist())
            .andExpect(jsonPath("$.data.user_info.limit").doesNotExist())
            .andExpect(jsonPath("$.data.user_info.traffic").doesNotExist())
            .andExpect(jsonPath("$.data.user_info.group").doesNotExist())
    }

    @Test
    fun `password reset request delegates email without revealing account existence`() {
        mockMvc.perform(
            post("/api/auth/password-reset/request")
                .param("email", "[REDACTED]"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("如果该邮箱已注册，密码重置邮件已发送"))

        verify(passwordResetService).requestReset("[REDACTED]")
    }

    @Test
    fun `password reset delegates matching passwords and reports revoked sessions`() {
        mockMvc.perform(
            post("/api/auth/password-reset")
                .param("token", "one-time-reset-token")
                .param("new_password", "new-password-123")
                .param("confirm_password", "new-password-123"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.password_reset").value(true))
            .andExpect(jsonPath("$.data.sessions_revoked").value(true))

        verify(passwordResetService).resetPassword("one-time-reset-token", "new-password-123")
    }
}
