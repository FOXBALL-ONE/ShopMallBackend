package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import java.util.UUID

/**
 * Provides single-record and batch CRUD operations for [User] entities.
 *
 * Batch create, query, and update operations return a [List] of affected or retrieved users.
 * Delete operations return whether the requested deletion completed successfully.
 */
interface UserService {

    fun createUser(user: User): User

    fun createUsers(users: List<User>): List<User>

    fun getUserById(id: Long): User?

    fun getUsersByIds(ids: List<Long>): List<User>

    fun updateUser(user: User): User

    fun updateUsers(users: List<User>): List<User>

    fun getDeliveryAddresses(userId: Long): List<DeliveryAddressItem>?

    fun getDeliveryAddress(userId: Long, addressId: UUID): DeliveryAddressItem?

    fun createDeliveryAddress(userId: Long, address: DeliveryAddressItem): DeliveryAddressItem?

    fun updateDeliveryAddress(userId: Long, addressId: UUID, address: DeliveryAddressItem): DeliveryAddressItem?

    fun deleteDeliveryAddress(userId: Long, addressId: UUID): Boolean?

    fun deleteUserById(id: Long): Boolean

    fun deleteUsersByIds(ids: List<Long>): Boolean
}


