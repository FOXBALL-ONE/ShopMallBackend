package top.foxball.shopmall.service.impl

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.UserStatusException
import top.foxball.shopmall.repository.AnnouncementUserStateRepository
import top.foxball.shopmall.repository.CustomerReviewRepository
import top.foxball.shopmall.repository.LogisticsIdempotencyRepository
import top.foxball.shopmall.repository.OrderIdempotencyRepository
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.OutboxEventRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.ShipmentItemRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.ShipmentTrackRepository
import top.foxball.shopmall.repository.SupportTicketMessageAttachmentRepository
import top.foxball.shopmall.repository.SupportTicketMessageRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.FileService
import top.foxball.shopmall.service.UserService
import java.time.LocalDateTime
import java.util.UUID

/** 用户资料与配送地址的业务实现，负责所有权校验、新用户密码加密和账户状态变化后的会话撤销。 */
@Service
@Transactional(readOnly = true)
class UserServiceImpl(
    private val userRepository: UserRepository,
    private val loginTokenAuthentication: LoginTokenAuthentication,
    private val passwordEncoder: PasswordEncoder,
    private val shoppingCartRepository: ShoppingCartRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val shipmentItemRepository: ShipmentItemRepository,
    private val shipmentTrackRepository: ShipmentTrackRepository,
    private val logisticsIdempotencyRepository: LogisticsIdempotencyRepository,
    private val orderIdempotencyRepository: OrderIdempotencyRepository,
    private val customerReviewRepository: CustomerReviewRepository,
    private val announcementUserStateRepository: AnnouncementUserStateRepository,
    private val supportTicketRepository: SupportTicketRepository,
    private val supportTicketMessageRepository: SupportTicketMessageRepository,
    private val supportTicketMessageAttachmentRepository: SupportTicketMessageAttachmentRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val fileService: FileService,
) : UserService {

    @Transactional
    override fun createUser(user: User): User {
        user.password = requireNotNull(passwordEncoder.encode(user.password)) { "密码编码失败" }
        return userRepository.save(user)
    }

    override fun getUserById(id: Long): User? = userRepository.findWithDeliveryAddressById(id)

    override fun getUserByUsername(username: String): User? = userRepository.findByUsername(username)

    override fun getUsernameById(id: Long): String? = userRepository.findById(id).orElse(null)?.username

    override fun getUsernamesByIds(ids: List<Long>): Map<Long, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            userRepository.findAllById(ids).associate { requireNotNull(it.id) to it.username }
        }

    override fun getUsersByIds(ids: List<Long>): List<User> =
        usersWithDeliveryAddressInRequestedOrder(ids)

    @Transactional
    override fun updateUser(user: User): User {
        val previousUser = user.id?.let { userRepository.findById(it).orElse(null) }
        val revokeSessions = previousUser != null && shouldRevokeSessions(previousUser, user)
        val savedUser = userRepository.save(user.apply { updatedAt = LocalDateTime.now() })
        if (revokeSessions) {
            loginTokenAuthentication.revokeAll(savedUser.id!!)
        }
        return savedUser
    }

    @Transactional
    override fun updateUsers(users: List<User>): List<User> {
        val previousUsers = userRepository.findAllById(users.mapNotNull(User::id))
            .associateBy { it.id }
        val userIdsToRevoke = users.mapNotNull { user ->
            val previousUser = user.id?.let(previousUsers::get)
            user.id?.takeIf { previousUser != null && shouldRevokeSessions(previousUser, user) }
        }.distinct()
        val savedUsers = userRepository.saveAll(users.onEach { it.updatedAt = LocalDateTime.now() }).toList()
        userIdsToRevoke.forEach(loginTokenAuthentication::revokeAll)
        return savedUsers
    }

    override fun getDeliveryAddresses(userId: Long): List<DeliveryAddressItem>? =
        userRepository.findWithDeliveryAddressById(userId)?.deliveryAddress?.toList()

    override fun getDeliveryAddress(userId: Long, addressId: UUID): DeliveryAddressItem? =
        userRepository.findWithDeliveryAddressById(userId)?.let { user ->
            user.deliveryAddress.firstOrNull { it.id == addressId }
                ?: run {
                    requireAddressOwner(userId, addressId)
                    null
                }
        }

    @Transactional
    override fun createDeliveryAddress(userId: Long, address: DeliveryAddressItem): DeliveryAddressItem? {
        val user = userRepository.findWithDeliveryAddressById(userId) ?: return null
        if (user.deliveryAddress.size >= MAX_DELIVERY_ADDRESSES) {
            throw ParamErrorException("配送地址最多保存 $MAX_DELIVERY_ADDRESSES 个")
        }

        val savedAddress = address.copy(id = UUID.randomUUID())
        if (user.deliveryAddress.isEmpty() || savedAddress.isDefault) {
            user.deliveryAddress.forEach { it.isDefault = false }
            savedAddress.isDefault = true
        }
        user.deliveryAddress.add(savedAddress)
        return savedAddress
    }

    @Transactional
    override fun updateDeliveryAddress(
        userId: Long,
        addressId: UUID,
        address: DeliveryAddressItem,
    ): DeliveryAddressItem? {
        val user = userRepository.findWithDeliveryAddressById(userId) ?: return null
        val index = user.deliveryAddress.indexOfFirst { it.id == addressId }
        if (index < 0) {
            requireAddressOwner(userId, addressId)
            return null
        }

        val currentAddress = user.deliveryAddress[index]
        val hasAnotherDefault = user.deliveryAddress.any { it.id != addressId && it.isDefault }
        val savedAddress = address.copy(
            id = addressId,
            isDefault = address.isDefault || (currentAddress.isDefault && !hasAnotherDefault),
        )
        if (savedAddress.isDefault) {
            user.deliveryAddress.forEach { it.isDefault = false }
        }
        user.deliveryAddress[index] = savedAddress
        return savedAddress
    }

    @Transactional
    override fun saveDefaultDeliveryAddress(userId: Long, address: DeliveryAddressItem): DeliveryAddressItem? {
        val user = userRepository.findWithDeliveryAddressById(userId) ?: return null
        val savedAddress = user.deliveryAddress.firstOrNull {
            it.name == address.name &&
                it.phone == address.phone &&
                it.company == address.company &&
                it.country == address.country &&
                it.stateOrProvince == address.stateOrProvince &&
                it.city == address.city &&
                it.district == address.district &&
                it.postalCode == address.postalCode &&
                it.address1 == address.address1 &&
                it.address2 == address.address2 &&
                it.deliveryInstructions == address.deliveryInstructions
        } ?: run {
            if (user.deliveryAddress.size >= MAX_DELIVERY_ADDRESSES) {
                throw ParamErrorException("配送地址最多保存 $MAX_DELIVERY_ADDRESSES 个")
            }
            address.copy(id = UUID.randomUUID()).also(user.deliveryAddress::add)
        }
        user.deliveryAddress.forEach { it.isDefault = it.id == savedAddress.id }
        return savedAddress
    }

    @Transactional
    override fun deleteDeliveryAddress(userId: Long, addressId: UUID): Boolean? {
        val user = userRepository.findWithDeliveryAddressById(userId) ?: return null
        val index = user.deliveryAddress.indexOfFirst { it.id == addressId }
        if (index < 0) {
            requireAddressOwner(userId, addressId)
            return false
        }

        val wasDefault = user.deliveryAddress[index].isDefault
        user.deliveryAddress.removeAt(index)
        if (wasDefault && user.deliveryAddress.isNotEmpty()) {
            user.deliveryAddress.first().isDefault = true
        }
        return true
    }

    @Transactional
    override fun deleteUserById(id: Long): Boolean {
        val user = userRepository.findByIdForUpdate(id) ?: return false
        if (user.status != Status.DELETED) {
            user.status = Status.DELETED
            user.enabled = false
            userRepository.save(user)
        }
        loginTokenAuthentication.revokeAll(id)
        return true
    }

    /**
     * Deletes all requested users atomically. If any requested ID does not exist,
     * no user is deleted and the method returns false.
     */
    @Transactional
    override fun deleteUsersByIds(ids: List<Long>): Boolean {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return true

        val users = lockedUsersInRequestedOrder(distinctIds)
        if (users.size != distinctIds.size) return false

        users.forEach {
            it.status = Status.DELETED
            it.enabled = false
        }
        userRepository.saveAll(users)
        distinctIds.forEach(loginTokenAuthentication::revokeAll)
        return true
    }

    @Transactional
    override fun purgeUserById(id: Long): Boolean {
        val user = userRepository.findByIdForUpdate(id) ?: return false
        if (user.status != Status.DELETED) {
            throw UserStatusException("只有状态为 DELETED（已删除）的用户才能彻底删除")
        }
        deleteRelatedData(listOf(id))
        userRepository.delete(user)
        loginTokenAuthentication.revokeAll(id)
        return true
    }

    @Transactional
    override fun purgeUsersByIds(ids: List<Long>): Boolean {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return true

        val users = lockedUsersInRequestedOrder(distinctIds)
        if (users.size != distinctIds.size) return false
        if (users.any { it.status != Status.DELETED }) {
            throw UserStatusException("只有状态为 DELETED（已删除）的用户才能彻底删除")
        }

        deleteRelatedData(distinctIds)
        userRepository.deleteAll(users)
        distinctIds.forEach(loginTokenAuthentication::revokeAll)
        return true
    }

    /**
     * 按外键依赖从子表到主表清理用户数据，避免订单、运单和工单留下孤儿记录。
     * 所有删除均处于调用方事务中；用户行在进入这里前已经按稳定顺序加锁。
     */
    private fun deleteRelatedData(userIds: Collection<Long>) {
        val orderIds = orderRepository.findIdsByCustomerIdIn(userIds)
        val shipmentIds = if (orderIds.isEmpty()) {
            emptyList()
        } else {
            shipmentRepository.findIdsByOrderIdIn(orderIds)
        }

        val ticketIds = (
            supportTicketRepository.findIdsByCustomerIdIn(userIds) +
                if (orderIds.isEmpty()) emptyList() else supportTicketRepository.findIdsByOrderIdIn(orderIds)
            ).distinct()
        val messageIds = (
            if (ticketIds.isEmpty()) emptyList() else supportTicketMessageRepository.findIdsByTicketIdIn(ticketIds)
            ) + supportTicketMessageRepository.findIdsBySenderIdIn(userIds)
        val distinctMessageIds = messageIds.distinct()
        if (distinctMessageIds.isNotEmpty()) {
            supportTicketMessageAttachmentRepository.deleteAllByMessageIdIn(distinctMessageIds)
            supportTicketMessageRepository.deleteAllByIdIn(distinctMessageIds)
        }
        if (ticketIds.isNotEmpty()) {
            supportTicketRepository.deleteAllByIdIn(ticketIds)
        }
        supportTicketRepository.clearHandledByIn(userIds)

        if (shipmentIds.isNotEmpty()) {
            outboxEventRepository.deleteAllByAggregateTypeAndAggregateIdIn("SHIPMENT", shipmentIds)
            logisticsIdempotencyRepository.deleteAllByShipmentIdIn(shipmentIds)
            shipmentTrackRepository.deleteAllByShipmentIdIn(shipmentIds)
            shipmentItemRepository.deleteAllByShipmentIdIn(shipmentIds)
            shipmentRepository.deleteAllByIdIn(shipmentIds)
        }
        logisticsIdempotencyRepository.deleteAllByActorIdIn(userIds)

        if (orderIds.isNotEmpty()) {
            outboxEventRepository.deleteAllByAggregateTypeAndAggregateIdIn("ORDER", orderIds)
            orderItemRepository.deleteAllByOrderIdIn(orderIds)
            orderRepository.deleteAllByIdIn(orderIds)
        }
        orderIdempotencyRepository.deleteAllByCustomerIdIn(userIds)
        customerReviewRepository.deleteAllByCustomerIdIn(userIds)
        announcementUserStateRepository.deleteAllByUserIdIn(userIds)
        shoppingCartRepository.deleteAllByCustomerIdIn(userIds)
        fileService.deleteAllByOwnerIds(userIds)
    }

    private fun lockedUsersInRequestedOrder(ids: List<Long>): List<User> {
        val usersById = userRepository.findAllByIdInForUpdate(ids).associateBy { it.id }
        return ids.mapNotNull(usersById::get)
    }

    private fun usersWithDeliveryAddressInRequestedOrder(ids: List<Long>): List<User> {
        if (ids.isEmpty()) return emptyList()

        val usersById = userRepository.findAllWithDeliveryAddressByIdIn(ids).associateBy { it.id }
        return ids.distinct().mapNotNull(usersById::get)
    }

    /** 地址只属于创建它的用户；不存在的地址仍由调用方按 404 处理。 */
    private fun requireAddressOwner(userId: Long, addressId: UUID) {
        if (userRepository.findUserIdsByDeliveryAddressId(addressId).any { it != userId }) {
            throw ForbiddenException("只能访问自己的配送地址")
        }
    }

    /** 密码、登录准入或账号状态变化后，旧会话均不得继续使用。 */
    private fun shouldRevokeSessions(previousUser: User, updatedUser: User): Boolean =
        previousUser.password != updatedUser.password ||
            previousUser.role != updatedUser.role ||
            !updatedUser.enabled ||
            updatedUser.status != Status.ACTIVE

    private companion object {
        const val MAX_DELIVERY_ADDRESSES = 20
    }
}
