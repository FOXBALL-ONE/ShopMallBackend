package top.foxball.shopmall.service.impl

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.repository.UserRepository
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
        userRepository.findWithDeliveryAddressById(userId)
            ?.deliveryAddress
            ?.firstOrNull { it.id == addressId }

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
        if (index < 0) return null

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
    override fun deleteDeliveryAddress(userId: Long, addressId: UUID): Boolean? {
        val user = userRepository.findWithDeliveryAddressById(userId) ?: return null
        val index = user.deliveryAddress.indexOfFirst { it.id == addressId }
        if (index < 0) return false

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
        shoppingCartRepository.deleteByCustomerId(id)
        userRepository.delete(user)
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

        shoppingCartRepository.deleteAllByCustomerIdIn(distinctIds)
        userRepository.deleteAll(users)
        distinctIds.forEach(loginTokenAuthentication::revokeAll)
        return true
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
