package top.foxball.shopmall.controller

import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.service.MailService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder
import kotlin.test.assertTrue

class UserControllerTest {
    @Test
    fun `registration persists verified email after code succeeds`() {
        val userService = mock(UserService::class.java)
        val mailService = mock(MailService::class.java)
        var createdUser: User? = null
        `when`(userService.createUser(anyUser())).thenAnswer { invocation ->
            invocation.getArgument<User>(0).also { createdUser = it }.apply { id = 7 }
        }
        val mockMvc = MockMvcBuilders.standaloneSetup(
            UserController(userService, mailService, ResponseBuilder()),
        ).build()

        mockMvc.perform(
            post("/api/users/Register")
                .header("User-Agent", "Browser")
                .param("email", "customer@example.com")
                .param("username", "customer")
                .param("password", "password123")
                .param("verification_code", "123456"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.email_verified").value(true))

        verify(mailService).verifyCode("customer@example.com", "123456", "Browser", null)
        assertTrue(requireNotNull(createdUser).emailVerified)
    }

    @Test
    fun `registration rejects a verification code that is not six digits`() {
        val controller = UserController(
            mock(UserService::class.java),
            mock(MailService::class.java),
            ResponseBuilder(),
        )
        val method = UserController::class.java.getMethod(
            "createUser",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val violations = Validation.buildDefaultValidatorFactory().use { factory ->
            factory.validator.forExecutables().validateParameters(
                controller,
                method,
                arrayOf<Any?>("Browser", "[REDACTED]", "customer", "password123", "12ab", null, null, false),
            )
        }

        assertTrue(violations.any { it.propertyPath.toString().endsWith("verificationCode") })
    }

    private fun anyUser(): User = org.mockito.ArgumentMatchers.any(User::class.java) ?: User()
}
