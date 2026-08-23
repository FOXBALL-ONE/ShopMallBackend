package top.foxball.shopmall.service.impl

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.UserStatusException
import top.foxball.shopmall.handler.UserAlreadyExistsException
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminUserQuery
import top.foxball.shopmall.service.AdminUserService
import top.foxball.shopmall.service.BatchUpdateAdminUsersCommand
import top.foxball.shopmall.service.CreateAdminUserCommand
import top.foxball.shopmall.service.UpdateAdminUserCommand
import top.foxball.shopmall.service.UserService

@Service
class AdminUserServiceImpl(
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val adminAccessService: AdminAccessService,
) : AdminUserService {
    override fun list(adminId: Long, query: AdminUserQuery): Page<User> {
        adminAccessService.requireAdmin(adminId)
        val keyword = query.keyword?.trim()?.takeIf(String::isNotEmpty).orEmpty()
        return userRepository.findAllForAdmin(
            keyword = keyword,
            role = query.role,
            status = query.status,
            enabled = query.enabled,
            pageable = PageRequest.of(query.page.coerceAtLeast(0), query.size.coerceIn(1, 100)),
        )
    }

    override fun get(adminId: Long, userId: Long): User? {
        adminAccessService.requireAdmin(adminId)
        return userRepository.findById(userId).orElse(null)
    }

    override fun create(adminId: Long, command: CreateAdminUserCommand): User {
        adminAccessService.requireAdmin(adminId)
        val email = command.email.trim().lowercase()
        val username = command.username.trim()
        ensureUnique(email, username)
        if (command.status == Status.DELETED) {
            throw ParamErrorException("新建用户不能直接使用 DELETED（已删除）状态，请先创建后再执行逻辑删除")
        }
        ensureStatusAllowsLogin(command.status, command.enabled)
        return userService.createUser(
            User(
                email = email,
                username = username,
                password = command.password,
                firstName = command.firstName.trim(),
                lastName = command.lastName.trim(),
                phone = command.phone?.trim()?.takeIf(String::isNotEmpty),
                avatar = command.avatar?.trim()?.takeIf(String::isNotEmpty),
                locale = command.locale?.trim()?.takeIf(String::isNotEmpty),
                currency = command.currency?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
                birthday = command.birthday,
                emailVerified = command.emailVerified,
                marketingConsent = command.marketingConsent,
                role = command.role,
                enabled = command.enabled,
                status = command.status,
            ),
        )
    }

    override fun update(adminId: Long, userId: Long, command: UpdateAdminUserCommand): User? {
        adminAccessService.requireAdmin(adminId)
        val user = userRepository.findById(userId).orElse(null) ?: return null
        ensureSelfRemainsUsable(adminId, userId, command.role, command.enabled, command.status)
        val email = command.email.trim().lowercase()
        val username = command.username.trim()
        ensureUnique(email, username, userId)
        ensureStatusTransitionAllowed(user.status, command.status)
        ensureStatusAllowsLogin(command.status, command.enabled)
        user.email = email
        user.username = username
        user.firstName = command.firstName.trim()
        user.lastName = command.lastName.trim()
        user.phone = command.phone?.trim()?.takeIf(String::isNotEmpty)
        user.avatar = command.avatar?.trim()?.takeIf(String::isNotEmpty)
        user.locale = command.locale?.trim()?.takeIf(String::isNotEmpty)
        user.currency = command.currency?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        user.birthday = command.birthday
        user.emailVerified = command.emailVerified
        user.marketingConsent = command.marketingConsent
        user.role = command.role
        user.enabled = command.enabled
        user.status = command.status
        return userService.updateUser(user)
    }

    override fun updateBatch(
        adminId: Long,
        userIds: List<Long>,
        command: BatchUpdateAdminUsersCommand,
    ): List<User>? {
        adminAccessService.requireAdmin(adminId)
        if (
            command.role == null && command.enabled == null && command.status == null &&
            command.emailVerified == null && command.marketingConsent == null
        ) {
            throw ParamErrorException("至少提供一个批量更新字段")
        }
        val distinctIds = userIds.distinct()
        val usersById = userRepository.findAllById(distinctIds).associateBy { requireNotNull(it.id) }
        if (usersById.size != distinctIds.size) return null
        val users = distinctIds.map(usersById::getValue)
        users.forEach { user ->
            val userId = requireNotNull(user.id)
            val nextRole = command.role ?: user.role
            val nextStatus = command.status ?: user.status
            val nextEnabled = command.enabled ?: user.enabled
            ensureSelfRemainsUsable(adminId, userId, nextRole, nextEnabled, nextStatus)
            ensureStatusTransitionAllowed(user.status, nextStatus)
            ensureStatusAllowsLogin(nextStatus, nextEnabled)
            user.role = nextRole
            user.status = nextStatus
            user.enabled = nextEnabled
            command.emailVerified?.let { user.emailVerified = it }
            command.marketingConsent?.let { user.marketingConsent = it }
        }
        return userService.updateUsers(users)
    }

    override fun delete(adminId: Long, userId: Long): Long? {
        adminAccessService.requireAdmin(adminId)
        if (adminId == userId) throw ForbiddenException("不能删除当前登录的管理员")
        return userId.takeIf(userService::deleteUserById)
    }

    override fun deleteBatch(adminId: Long, userIds: List<Long>): List<Long>? {
        adminAccessService.requireAdmin(adminId)
        val distinctIds = userIds.distinct()
        if (adminId in distinctIds) throw ForbiddenException("不能删除当前登录的管理员")
        if (!userService.deleteUsersByIds(distinctIds)) return null
        return distinctIds
    }

    override fun purge(adminId: Long, userId: Long): Long? {
        adminAccessService.requireAdmin(adminId)
        if (adminId == userId) throw ForbiddenException("不能删除当前登录的管理员")
        return userId.takeIf(userService::purgeUserById)
    }

    override fun purgeBatch(adminId: Long, userIds: List<Long>): List<Long>? {
        adminAccessService.requireAdmin(adminId)
        val distinctIds = userIds.distinct()
        if (adminId in distinctIds) throw ForbiddenException("不能删除当前登录的管理员")
        if (!userService.purgeUsersByIds(distinctIds)) return null
        return distinctIds
    }

    private fun ensureUnique(email: String, username: String, userId: Long? = null) {
        val usernameExists = if (userId == null) {
            userRepository.existsByUsername(username)
        } else {
            userRepository.existsByUsernameAndIdNot(username, userId)
        }
        if (usernameExists) throw UserAlreadyExistsException("用户名已存在")
        val emailExists = if (userId == null) {
            userRepository.existsByEmail(email)
        } else {
            userRepository.existsByEmailAndIdNot(email, userId)
        }
        if (emailExists) throw UserAlreadyExistsException("邮箱已存在")
    }

    private fun ensureStatusAllowsLogin(status: Status, enabled: Boolean) {
        if (enabled && status != Status.ACTIVE) {
            throw ParamErrorException(
                if (status == Status.DELETED) "已删除用户不能处于启用状态" else "停用用户不能处于启用状态",
            )
        }
    }

    private fun ensureStatusTransitionAllowed(current: Status, next: Status) {
        if (next == Status.DELETED && current != Status.DELETED) {
            throw UserStatusException("不能通过通用更新接口直接设置 DELETED（已删除）状态，请先执行逻辑删除操作")
        }
    }

    private fun ensureSelfRemainsUsable(
        adminId: Long,
        userId: Long,
        role: Role,
        enabled: Boolean,
        status: Status,
    ) {
        if (adminId == userId && (role != Role.ADMIN || !enabled || status != Status.ACTIVE)) {
            throw ForbiddenException("不能禁用、删除或降级当前登录的管理员")
        }
    }
}
