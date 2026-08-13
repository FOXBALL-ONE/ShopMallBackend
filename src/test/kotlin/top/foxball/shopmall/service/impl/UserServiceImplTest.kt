package top.foxball.shopmall.service.impl

import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UserServiceImplTest {
    private val userRepository = mock(UserRepository::class.java)
    private val loginTokenAuthentication = mock(LoginTokenAuthentication::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val shoppingCartRepository = mock(ShoppingCartRepository::class.java)
    private val service = UserServiceImpl(
        userRepository,
        loginTokenAuthentication,
        passwordEncoder,
        shoppingCartRepository,
    )

    @Test
    fun `deleting a user locks it before removing their cart`() {
        val user = User(id = 42)
        `when`(userRepository.findByIdForUpdate(42)).thenReturn(user)

        assertTrue(service.deleteUserById(42))

        val order = inOrder(userRepository, shoppingCartRepository, loginTokenAuthentication)
        order.verify(userRepository).findByIdForUpdate(42)
        order.verify(shoppingCartRepository).deleteByCustomerId(42)
        order.verify(userRepository).delete(user)
        order.verify(loginTokenAuthentication).revokeAll(42)
    }

    @Test
    fun `batch deletion locks all users before removing their carts`() {
        val first = User(id = 5)
        val second = User(id = 9)
        `when`(userRepository.findAllByIdInForUpdate(listOf(5, 9))).thenReturn(listOf(first, second))

        assertTrue(service.deleteUsersByIds(listOf(5, 9, 5)))

        val order = inOrder(userRepository, shoppingCartRepository, loginTokenAuthentication)
        order.verify(userRepository).findAllByIdInForUpdate(listOf(5, 9))
        order.verify(shoppingCartRepository).deleteAllByCustomerIdIn(listOf(5, 9))
        order.verify(userRepository).deleteAll(listOf(first, second))
        order.verify(loginTokenAuthentication).revokeAll(5)
        order.verify(loginTokenAuthentication).revokeAll(9)
        verify(userRepository).findAllByIdInForUpdate(listOf(5, 9))
    }

    @Test
    fun `changing a user role revokes existing sessions`() {
        val previous = User(id = 42, role = Role.ADMIN)
        val updated = User(id = 42, role = Role.CUSTOMER)
        `when`(userRepository.findById(42)).thenReturn(java.util.Optional.of(previous))
        `when`(userRepository.save(updated)).thenReturn(updated)

        service.updateUser(updated)

        verify(loginTokenAuthentication).revokeAll(42)
    }

    @Test
    fun `deactivating a user revokes existing sessions`() {
        val previous = User(id = 42, role = Role.CUSTOMER, enabled = true, status = Status.ACTIVE)
        val updated = User(id = 42, role = Role.CUSTOMER, enabled = false, status = Status.INACTIVE)
        `when`(userRepository.findById(42)).thenReturn(java.util.Optional.of(previous))
        `when`(userRepository.save(updated)).thenReturn(updated)

        service.updateUser(updated)

        verify(loginTokenAuthentication).revokeAll(42)
    }

    @Test
    fun `setting an existing delivery address as default clears the previous default`() {
        val previousDefault = DeliveryAddressItem(
            id = UUID.randomUUID(),
            name = "Previous",
            phone = "+14155550111",
            country = "US",
            city = "Austin",
            address1 = "1 First St",
            isDefault = true,
        )
        val target = DeliveryAddressItem(
            id = UUID.randomUUID(),
            name = "Target",
            phone = "+14155550222",
            country = "US",
            city = "Austin",
            address1 = "2 Second St",
        )
        val user = User(id = 42, deliveryAddress = mutableListOf(previousDefault, target))
        `when`(userRepository.findWithDeliveryAddressById(42)).thenReturn(user)

        val result = service.saveDefaultDeliveryAddress(42, target.copy(id = UUID.randomUUID(), isDefault = true))

        assertSame(target, result)
        assertTrue(target.isDefault)
        assertEquals(false, previousDefault.isDefault)
        assertEquals(2, user.deliveryAddress.size)
    }

    @Test
    fun `setting an order address as default saves it when it is not in the address book`() {
        val previousDefault = DeliveryAddressItem(
            name = "Previous",
            phone = "+14155550111",
            country = "US",
            city = "Austin",
            address1 = "1 First St",
            isDefault = true,
        )
        val user = User(id = 42, deliveryAddress = mutableListOf(previousDefault))
        `when`(userRepository.findWithDeliveryAddressById(42)).thenReturn(user)

        val result = service.saveDefaultDeliveryAddress(
            42,
            DeliveryAddressItem(
                name = "Order Recipient",
                phone = "+14155550333",
                company = "Pelissa",
                country = "US",
                stateOrProvince = "TX",
                city = "Austin",
                postalCode = "78701",
                address1 = "3 Third St",
                address2 = "Suite 4",
                isDefault = true,
                deliveryInstructions = "Front desk",
            ),
        )

        assertEquals(2, user.deliveryAddress.size)
        assertEquals(false, previousDefault.isDefault)
        assertTrue(requireNotNull(result).isDefault)
        assertEquals("3 Third St", result.address1)
    }

    @Test
    fun `returns null for a missing delivery address`() {
        val addressId = UUID.randomUUID()
        `when`(userRepository.findWithDeliveryAddressById(42)).thenReturn(User(id = 42))
        `when`(userRepository.findUserIdsByDeliveryAddressId(addressId)).thenReturn(emptyList())

        assertNull(service.getDeliveryAddress(42, addressId))
    }

    @Test
    fun `rejects access to another users delivery address`() {
        val addressId = UUID.randomUUID()
        `when`(userRepository.findWithDeliveryAddressById(42)).thenReturn(
            User(id = 42, deliveryAddress = mutableListOf<DeliveryAddressItem>()),
        )
        `when`(userRepository.findUserIdsByDeliveryAddressId(addressId)).thenReturn(listOf(43))

        assertFailsWith<ForbiddenException> {
            service.getDeliveryAddress(42, addressId)
        }
    }
}
