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
import top.foxball.shopmall.handler.UserStatusException
import top.foxball.shopmall.repository.AnnouncementUserStateRepository
import top.foxball.shopmall.repository.CustomerReviewRepository
import top.foxball.shopmall.repository.LogisticsIdempotencyRepository
import top.foxball.shopmall.repository.OrderIdempotencyRepository
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.OutboxEventRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.ShipmentTrackRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.SupportTicketMessageAttachmentRepository
import top.foxball.shopmall.repository.SupportTicketMessageRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.FileService
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
    private val orderRepository = mock(OrderRepository::class.java)
    private val orderItemRepository = mock(OrderItemRepository::class.java)
    private val shipmentRepository = mock(ShipmentRepository::class.java)
    private val shipmentItemRepository = mock(ShipmentItemRepository::class.java)
    private val shipmentTrackRepository = mock(ShipmentTrackRepository::class.java)
    private val logisticsIdempotencyRepository = mock(LogisticsIdempotencyRepository::class.java)
    private val orderIdempotencyRepository = mock(OrderIdempotencyRepository::class.java)
    private val customerReviewRepository = mock(CustomerReviewRepository::class.java)
    private val announcementUserStateRepository = mock(AnnouncementUserStateRepository::class.java)
    private val supportTicketRepository = mock(SupportTicketRepository::class.java)
    private val supportTicketMessageRepository = mock(SupportTicketMessageRepository::class.java)
    private val supportTicketMessageAttachmentRepository =
        mock(SupportTicketMessageAttachmentRepository::class.java)
    private val outboxEventRepository = mock(OutboxEventRepository::class.java)
    private val fileService = mock(FileService::class.java)
    private val service = UserServiceImpl(
        userRepository,
        loginTokenAuthentication,
        passwordEncoder,
        shoppingCartRepository,
        orderRepository,
        orderItemRepository,
        shipmentRepository,
        shipmentItemRepository,
        shipmentTrackRepository,
        logisticsIdempotencyRepository,
        orderIdempotencyRepository,
        customerReviewRepository,
        announcementUserStateRepository,
        supportTicketRepository,
        supportTicketMessageRepository,
        supportTicketMessageAttachmentRepository,
        outboxEventRepository,
        fileService,
    )

    @Test
    fun `deleting a user marks it deleted and revokes sessions without removing related data`() {
        val user = User(id = 42, status = Status.ACTIVE, enabled = true)
        `when`(userRepository.findByIdForUpdate(42)).thenReturn(user)

        assertTrue(service.deleteUserById(42))

        val order = inOrder(userRepository, loginTokenAuthentication)
        order.verify(userRepository).findByIdForUpdate(42)
        order.verify(userRepository).save(user)
        order.verify(loginTokenAuthentication).revokeAll(42)
        assertEquals(Status.DELETED, user.status)
        assertEquals(false, user.enabled)
    }

    @Test
    fun `password update encodes password saves user and revokes all sessions`() {
        val user = User(id = 42)
        `when`(userRepository.findByIdForUpdate(42)).thenReturn(user)
        `when`(passwordEncoder.encode("new-password")).thenReturn("encoded-password")

        assertTrue(service.updatePassword(42, "new-password"))

        assertEquals("encoded-password", user.password)
        val order = inOrder(passwordEncoder, userRepository, loginTokenAuthentication)
        order.verify(passwordEncoder).encode("new-password")
        order.verify(userRepository).save(user)
        order.verify(loginTokenAuthentication).revokeAll(42)
    }

    @Test
    fun `password update returns false without encoding when user does not exist`() {
        `when`(userRepository.findByIdForUpdate(404)).thenReturn(null)

        assertEquals(false, service.updatePassword(404, "new-password"))

        verify(userRepository).findByIdForUpdate(404)
        org.mockito.Mockito.verifyNoInteractions(passwordEncoder, loginTokenAuthentication)
    }

    @Test
    fun `batch deletion locks all users before marking them deleted`() {
        val first = User(id = 5)
        val second = User(id = 9)
        `when`(userRepository.findAllByIdInForUpdate(listOf(5, 9))).thenReturn(listOf(first, second))

        assertTrue(service.deleteUsersByIds(listOf(5, 9, 5)))

        val order = inOrder(userRepository, loginTokenAuthentication)
        order.verify(userRepository).findAllByIdInForUpdate(listOf(5, 9))
        order.verify(userRepository).saveAll(listOf(first, second))
        order.verify(loginTokenAuthentication).revokeAll(5)
        order.verify(loginTokenAuthentication).revokeAll(9)
        assertEquals(listOf(Status.DELETED, Status.DELETED), listOf(first.status, second.status))
        assertEquals(listOf(false, false), listOf(first.enabled, second.enabled))
        verify(userRepository).findAllByIdInForUpdate(listOf(5, 9))
    }

    @Test
    fun `purging a logically deleted user removes every discovered related aggregate`() {
        val user = User(id = 42, status = Status.DELETED, enabled = false)
        `when`(userRepository.findByIdForUpdate(42)).thenReturn(user)
        `when`(orderRepository.findIdsByCustomerIdIn(listOf(42))).thenReturn(listOf(100, 101))
        `when`(shipmentRepository.findIdsByOrderIdIn(listOf(100, 101))).thenReturn(listOf(200))
        `when`(supportTicketRepository.findIdsByCustomerIdIn(listOf(42))).thenReturn(listOf(300))
        `when`(supportTicketRepository.findIdsByOrderIdIn(listOf(100, 101))).thenReturn(listOf(301, 300))
        `when`(supportTicketMessageRepository.findIdsByTicketIdIn(listOf(300, 301))).thenReturn(listOf(400))
        `when`(supportTicketMessageRepository.findIdsBySenderIdIn(listOf(42))).thenReturn(listOf(401, 400))

        assertTrue(service.purgeUserById(42))

        verify(supportTicketMessageAttachmentRepository).deleteAllByMessageIdIn(listOf(400, 401))
        verify(supportTicketMessageRepository).deleteAllByIdIn(listOf(400, 401))
        verify(supportTicketRepository).deleteAllByIdIn(listOf(300, 301))
        verify(supportTicketRepository).clearHandledByIn(listOf(42))
        verify(outboxEventRepository).deleteAllByAggregateTypeAndAggregateIdIn("SHIPMENT", listOf(200))
        verify(logisticsIdempotencyRepository).deleteAllByShipmentIdIn(listOf(200))
        verify(shipmentTrackRepository).deleteAllByShipmentIdIn(listOf(200))
        verify(shipmentItemRepository).deleteAllByShipmentIdIn(listOf(200))
        verify(shipmentRepository).deleteAllByIdIn(listOf(200))
        verify(logisticsIdempotencyRepository).deleteAllByActorIdIn(listOf(42))
        verify(outboxEventRepository).deleteAllByAggregateTypeAndAggregateIdIn("ORDER", listOf(100, 101))
        verify(orderItemRepository).deleteAllByOrderIdIn(listOf(100, 101))
        verify(orderRepository).deleteAllByIdIn(listOf(100, 101))
        verify(orderIdempotencyRepository).deleteAllByCustomerIdIn(listOf(42))
        verify(customerReviewRepository).deleteAllByCustomerIdIn(listOf(42))
        verify(announcementUserStateRepository).deleteAllByUserIdIn(listOf(42))
        verify(shoppingCartRepository).deleteAllByCustomerIdIn(listOf(42))
        verify(fileService).deleteAllByOwnerIds(listOf(42L))
        verify(userRepository).delete(user)
        verify(loginTokenAuthentication).revokeAll(42)
    }

    @Test
    fun `physical deletion rejects a user that was not logically deleted`() {
        `when`(userRepository.findByIdForUpdate(42)).thenReturn(User(id = 42, status = Status.ACTIVE))

        assertFailsWith<UserStatusException> {
            service.purgeUserById(42)
        }
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
