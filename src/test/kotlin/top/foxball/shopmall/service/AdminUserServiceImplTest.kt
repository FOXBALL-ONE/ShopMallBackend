package top.foxball.shopmall.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.UserStatusException
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.AdminUserServiceImpl
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminUserServiceImplTest {
    private lateinit var userRepository: UserRepository
    private lateinit var userService: UserService
    private lateinit var adminAccessService: AdminAccessService
    private lateinit var service: AdminUserServiceImpl

    @BeforeEach
    fun setUp() {
        userRepository = mock(UserRepository::class.java)
        userService = mock(UserService::class.java)
        adminAccessService = mock(AdminAccessService::class.java)
        service = AdminUserServiceImpl(userRepository, userService, adminAccessService)
    }

    @Test
    fun `list binds an absent keyword as a non-null empty string`() {
        val pageable = PageRequest.of(0, 25)
        `when`(userRepository.findAllForAdmin("", null, null, null, pageable))
            .thenReturn(Page.empty(pageable))

        val users = service.list(99, AdminUserQuery())

        assertTrue(users.isEmpty)
        verify(adminAccessService).requireAdmin(99)
        verify(userRepository).findAllForAdmin("", null, null, null, pageable)
    }

    @Test
    fun `create normalizes identity fields and delegates password encoding`() {
        `when`(userRepository.existsByUsername("alice")).thenReturn(false)
        `when`(userRepository.existsByEmail("alice@example.test")).thenReturn(false)
        `when`(userService.createUser(anyUser())).thenAnswer { invocation ->
            invocation.getArgument<User>(0).apply { id = 7 }
        }

        val created = service.create(
            99,
            CreateAdminUserCommand(
                email = " Alice@Example.Test ",
                username = " alice ",
                password = "password123",
                firstName = " Alice ",
                currency = "usd",
            ),
        )

        assertEquals(7, created.id)
        assertEquals("alice@example.test", created.email)
        assertEquals("alice", created.username)
        assertEquals("Alice", created.firstName)
        assertEquals("USD", created.currency)
        assertEquals("password123", created.password)
        verify(adminAccessService).requireAdmin(99)
        verify(userService).createUser(created)
    }

    @Test
    fun `current administrator cannot be disabled or demoted`() {
        val admin = User(id = 99, email = "admin@example.test", username = "admin", role = Role.ADMIN)
        `when`(userRepository.findById(99)).thenReturn(Optional.of(admin))

        assertThrows<ForbiddenException> {
            service.update(
                99,
                99,
                UpdateAdminUserCommand(
                    email = admin.email,
                    username = admin.username,
                    role = Role.CUSTOMER,
                    enabled = true,
                    status = Status.ACTIVE,
                ),
            )
        }

        verifyNoInteractions(userService)
    }

    @Test
    fun `batch update changes requested fields in stable request order`() {
        val alice = User(id = 7, username = "alice", role = Role.CUSTOMER)
        val bob = User(id = 8, username = "bob", role = Role.CUSTOMER)
        `when`(userRepository.findAllById(listOf(8L, 7L))).thenReturn(listOf(alice, bob))
        `when`(userService.updateUsers(anyUsers())).thenAnswer { it.getArgument<List<User>>(0) }

        val updated = service.updateBatch(
            adminId = 99,
            userIds = listOf(8, 7),
            command = BatchUpdateAdminUsersCommand(
                role = Role.ADMIN,
                enabled = false,
                status = Status.INACTIVE,
            ),
        )

        assertEquals(listOf(8L, 7L), updated?.map(User::id))
        updated.orEmpty().forEach {
            assertEquals(Role.ADMIN, it.role)
            assertEquals(Status.INACTIVE, it.status)
            assertFalse(it.enabled)
        }
    }

    @Test
    fun `batch update returns null without writing when any user is missing`() {
        val alice = User(id = 7, username = "alice")
        `when`(userRepository.findAllById(listOf(7L, 8L))).thenReturn(listOf(alice))

        val updated = service.updateBatch(
            99,
            listOf(7, 8),
            BatchUpdateAdminUsersCommand(enabled = false),
        )

        assertNull(updated)
        verifyNoInteractions(userService)
    }

    @Test
    fun `regular update cannot directly mark an active user as deleted`() {
        val customer = User(id = 7, email = "[REDACTED]", username = "alice", status = Status.ACTIVE)
        `when`(userRepository.findById(7)).thenReturn(Optional.of(customer))

        assertThrows<UserStatusException> {
            service.update(
                99,
                7,
                UpdateAdminUserCommand(
                    email = customer.email,
                    username = customer.username,
                    role = Role.CUSTOMER,
                    enabled = false,
                    status = Status.DELETED,
                ),
            )
        }

        verifyNoInteractions(userService)
    }

    @Test
    fun `batch update cannot directly mark an active user as deleted`() {
        val customer = User(id = 7, username = "alice", status = Status.ACTIVE)
        `when`(userRepository.findAllById(listOf(7L))).thenReturn(listOf(customer))

        assertThrows<UserStatusException> {
            service.updateBatch(99, listOf(7), BatchUpdateAdminUsersCommand(status = Status.DELETED))
        }

        verifyNoInteractions(userService)
    }

    @Test
    fun `creating a user cannot use deleted status`() {
        assertThrows<ParamErrorException> {
            service.create(
                99,
                CreateAdminUserCommand(
                    email = "[REDACTED]",
                    username = "alice",
                    password = "password123",
                    enabled = false,
                    status = Status.DELETED,
                ),
            )
        }

        verifyNoInteractions(userService)
    }

    @Test
    fun `delete logically removes the user through user service`() {
        val customer = User(id = 7, username = "alice", status = Status.ACTIVE, enabled = true)
        `when`(userService.deleteUserById(7)).thenReturn(true)

        val deleted = service.delete(99, 7)

        assertEquals(7L, deleted)
        verify(userService).deleteUserById(7)
        verifyNoInteractions(userRepository)
    }

    @Test
    fun `purge delegates physical removal through user service`() {
        `when`(userService.purgeUserById(7)).thenReturn(true)

        val purged = service.purge(99, 7)

        assertEquals(7L, purged)
        verify(userService).purgeUserById(7)
        verifyNoInteractions(userRepository)
    }

    @Test
    fun `inactive users cannot be enabled`() {
        `when`(userRepository.existsByUsername("alice")).thenReturn(false)
        `when`(userRepository.existsByEmail("[REDACTED]")).thenReturn(false)

        assertThrows<ParamErrorException> {
            service.create(
                99,
                CreateAdminUserCommand(
                    email = "[REDACTED]",
                    username = "alice",
                    password = "p".repeat(8),
                    enabled = true,
                    status = Status.INACTIVE,
                ),
            )
        }

        verifyNoInteractions(userService)
    }

    private fun anyUser(): User = any(User::class.java) ?: User()

    private fun anyUsers(): List<User> = org.mockito.ArgumentMatchers.anyList<User>() ?: emptyList()
}
