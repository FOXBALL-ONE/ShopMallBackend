package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.controller.admin.AdminUserController
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.service.AdminUserQuery
import top.foxball.shopmall.service.AdminUserService
import top.foxball.shopmall.service.BatchUpdateAdminUsersCommand
import top.foxball.shopmall.service.CreateAdminUserCommand
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDate
import java.time.LocalDateTime

class AdminUserControllerTest {
    private lateinit var adminUserService: AdminUserService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        adminUserService = mock(AdminUserService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminUserController(adminUserService, ResponseBuilder()),
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(99L, null)
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `admin user list forwards filters and returns snake case fields`() {
        val query = AdminUserQuery(0, 10, "alice", Role.CUSTOMER, Status.ACTIVE, true)
        val user = user(id = 7, username = "alice")
        `when`(adminUserService.list(99, query)).thenReturn(
            PageImpl(listOf(user), PageRequest.of(0, 10), 11),
        )

        mockMvc.perform(
            get("/admin/api/users")
                .param("page", "1")
                .param("size", "10")
                .param("keyword", "alice")
                .param("role", "CUSTOMER")
                .param("status", "ACTIVE")
                .param("enabled", "true"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.list[0].username").value("alice"))
            .andExpect(jsonPath("$.data.list[0].email_verified").value(true))
            .andExpect(jsonPath("$.data.pagination.total_items").value(11))
            .andExpect(jsonPath("$.data.pagination.total_pages").value(2))

        verify(adminUserService).list(99, query)
    }

    @Test
    fun `admin can create a user with explicit account properties`() {
        val birthday = LocalDate.of(1995, 4, 3)
        val command = CreateAdminUserCommand(
            email = "alice@example.test",
            username = "alice",
            password = "password123",
            firstName = "Alice",
            lastName = "Smith",
            phone = "+14155550123",
            locale = "en-US",
            currency = "usd",
            birthday = birthday,
            emailVerified = true,
            marketingConsent = true,
            role = Role.CUSTOMER,
            enabled = true,
            status = Status.ACTIVE,
        )
        `when`(adminUserService.create(99, command)).thenReturn(
            user(id = 7, username = "alice").apply {
                this.birthday = LocalDate.of(1995, 4, 3)
                this.locale = "en-US"
                this.currency = "USD"
            },
        )

        mockMvc.perform(
            post("/admin/api/users")
                .param("email", "alice@example.test")
                .param("username", "alice")
                .param("password", "password123")
                .param("first_name", "Alice")
                .param("last_name", "Smith")
                .param("phone", "+14155550123")
                .param("locale", "en-US")
                .param("currency", "usd")
                .param("birthday", "1995-04-03")
                .param("email_verified", "true")
                .param("marketing_consent", "true")
                .param("role", "CUSTOMER")
                .param("enabled", "true")
                .param("status", "ACTIVE"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(7))
            .andExpect(jsonPath("$.data.birthday").value("1995-04-03"))

        verify(adminUserService).create(99, command)
    }

    @Test
    fun `admin batch update binds ids and account fields`() {
        val command = BatchUpdateAdminUsersCommand(
            role = Role.ADMIN,
            enabled = false,
            status = Status.INACTIVE,
        )
        `when`(adminUserService.updateBatch(99, listOf(7, 8), command)).thenReturn(
            listOf(
                user(7, "alice").apply { role = Role.ADMIN; enabled = false; status = Status.INACTIVE },
                user(8, "bob").apply { role = Role.ADMIN; enabled = false; status = Status.INACTIVE },
            ),
        )

        mockMvc.perform(
            put("/admin/api/users/batch")
                .param("ids", "7", "8")
                .param("role", "ADMIN")
                .param("enabled", "false")
                .param("status", "INACTIVE"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updated").value(2))
            .andExpect(jsonPath("$.data.list[0].role").value("ADMIN"))

        verify(adminUserService).updateBatch(99, listOf(7, 8), command)
    }

    @Test
    fun `admin delete returns logical deleted state`() {
        `when`(adminUserService.delete(99, 7)).thenReturn(7L)

        mockMvc.perform(delete("/admin/api/users/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(7))
            .andExpect(jsonPath("$.data.status").value("DELETED"))
            .andExpect(jsonPath("$.data.enabled").value(false))
    }

    @Test
    fun `admin purge returns purged state`() {
        `when`(adminUserService.purge(99, 7)).thenReturn(7L)

        mockMvc.perform(delete("/admin/api/users/7/purge"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(7))
            .andExpect(jsonPath("$.data.status").value("PURGED"))
            .andExpect(jsonPath("$.data.enabled").value(false))

        verify(adminUserService).purge(99, 7)
    }

    private fun user(id: Long, username: String): User = User(
        id = id,
        email = "$username@example.test",
        username = username,
        firstName = username.replaceFirstChar(Char::uppercase),
        role = Role.CUSTOMER,
        status = Status.ACTIVE,
        enabled = true,
        emailVerified = true,
        createdAt = LocalDateTime.of(2026, 8, 6, 12, 0),
        updatedAt = LocalDateTime.of(2026, 8, 6, 12, 0),
    )
}
