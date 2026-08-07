package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertFailsWith
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.repository.UserRepository
import java.util.Optional

class AdminAccessServiceTest {
    @Test
    fun `admin and customer routes enforce their respective roles`() {
        val userRepository = mock(UserRepository::class.java)
        val accessService = AdminAccessService(userRepository)
        `when`(userRepository.findById(1)).thenReturn(Optional.of(User(role = Role.ADMIN)))
        `when`(userRepository.findById(2)).thenReturn(Optional.of(User(role = Role.CUSTOMER)))

        accessService.requireAdmin(1)
        accessService.requireCustomer(2)
        assertFailsWith<ForbiddenException> { accessService.requireAdmin(2) }
        assertFailsWith<ForbiddenException> { accessService.requireCustomer(1) }
    }

    @Test
    fun `customer update check locks customer and rejects non customer users`() {
        val userRepository = mock(UserRepository::class.java)
        val accessService = AdminAccessService(userRepository)
        `when`(userRepository.findByIdForUpdate(2)).thenReturn(User(role = Role.CUSTOMER))
        `when`(userRepository.findByIdForUpdate(1)).thenReturn(User(role = Role.ADMIN))
        `when`(userRepository.findByIdForUpdate(3)).thenReturn(null)

        accessService.requireCustomerForUpdate(2)
        verify(userRepository).findByIdForUpdate(2)

        val adminException = assertFailsWith<ForbiddenException> {
            accessService.requireCustomerForUpdate(1)
        }
        val missingUserException = assertFailsWith<ForbiddenException> {
            accessService.requireCustomerForUpdate(3)
        }

        kotlin.test.assertEquals("仅普通客户可以执行此操作", adminException.message)
        kotlin.test.assertEquals("仅普通客户可以执行此操作", missingUserException.message)
    }

    @Test
    fun `disabled or deleted administrator cannot retain backend access`() {
        val userRepository = mock(UserRepository::class.java)
        val accessService = AdminAccessService(userRepository)
        `when`(userRepository.findById(1)).thenReturn(
            Optional.of(User(role = Role.ADMIN, enabled = false, status = Status.INACTIVE)),
        )
        `when`(userRepository.findById(2)).thenReturn(
            Optional.of(User(role = Role.ADMIN, enabled = false, status = Status.DELETED)),
        )

        assertFailsWith<ForbiddenException> { accessService.requireAdmin(1) }
        assertFailsWith<ForbiddenException> { accessService.requireAdmin(2) }
    }
}
