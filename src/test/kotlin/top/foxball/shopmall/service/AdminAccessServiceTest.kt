package top.foxball.shopmall.service

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertFailsWith
import top.foxball.shopmall.entity.jdbc.Role
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
}
