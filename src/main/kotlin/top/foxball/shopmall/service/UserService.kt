package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import java.util.UUID

/** 用户与配送地址服务，统一处理用户资料的批量操作和地址归属关系。 */
interface UserService {

    /** 创建单个用户；明文密码会被加密后持久化。 */
    fun createUser(user: User): User

    /** 按主键查询用户；不存在时返回 `null`。 */
    fun getUserById(id: Long): User?

    /** 按用户名精确查询用户；不存在时返回 `null`。 */
    fun getUserByUsername(username: String): User?

    /** 按主键查询用户名；不存在时返回 `null`。 */
    fun getUsernameById(id: Long): String?

    /** 批量查询用户名，以用户主键为键，不加载配送地址。 */
    fun getUsernamesByIds(ids: List<Long>): Map<Long, String>

    /** 按主键集合查询用户，不保证包含所有传入主键。 */
    fun getUsersByIds(ids: List<Long>): List<User>

    /** 更新单个用户；账号安全属性变化时由实现层撤销已有会话。 */
    fun updateUser(user: User): User

    /** 批量更新用户；账号安全属性变化时由实现层撤销已有会话。 */
    fun updateUsers(users: List<User>): List<User>

    /** 查询用户的全部配送地址；用户不存在时返回 `null`。 */
    fun getDeliveryAddresses(userId: Long): List<DeliveryAddressItem>?

    /** 查询用户拥有的指定配送地址；用户或地址不存在时返回 `null`。 */
    fun getDeliveryAddress(userId: Long, addressId: UUID): DeliveryAddressItem?

    /** 为指定用户新增配送地址；用户不存在时返回 `null`。 */
    fun createDeliveryAddress(userId: Long, address: DeliveryAddressItem): DeliveryAddressItem?

    /** 更新用户拥有的配送地址；用户或地址不存在时返回 `null`。 */
    fun updateDeliveryAddress(userId: Long, addressId: UUID, address: DeliveryAddressItem): DeliveryAddressItem?

    /** 删除用户拥有的配送地址；用户不存在时返回 `null`。 */
    fun deleteDeliveryAddress(userId: Long, addressId: UUID): Boolean?

    /** 删除单个用户并撤销其会话；用户不存在时返回 `false`。 */
    fun deleteUserById(id: Long): Boolean

    /** 批量删除用户并撤销相关会话。 */
    fun deleteUsersByIds(ids: List<Long>): Boolean
}
