package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import java.time.LocalDate

data class AdminUserQuery(
    val page: Int = 0,
    val size: Int = 25,
    val keyword: String? = null,
    val role: Role? = null,
    val status: Status? = null,
    val enabled: Boolean? = null,
)

data class CreateAdminUserCommand(
    val email: String,
    val username: String,
    val password: String,
    val firstName: String = "",
    val lastName: String = "",
    val phone: String? = null,
    val avatar: String? = null,
    val locale: String? = null,
    val currency: String? = null,
    val birthday: LocalDate? = null,
    val emailVerified: Boolean = false,
    val marketingConsent: Boolean = false,
    val role: Role = Role.CUSTOMER,
    val enabled: Boolean = true,
    val status: Status = Status.ACTIVE,
)

data class UpdateAdminUserCommand(
    val email: String,
    val username: String,
    val firstName: String = "",
    val lastName: String = "",
    val phone: String? = null,
    val avatar: String? = null,
    val locale: String? = null,
    val currency: String? = null,
    val birthday: LocalDate? = null,
    val emailVerified: Boolean = false,
    val marketingConsent: Boolean = false,
    val role: Role,
    val enabled: Boolean,
    val status: Status,
)

data class BatchUpdateAdminUsersCommand(
    val role: Role? = null,
    val enabled: Boolean? = null,
    val status: Status? = null,
    val emailVerified: Boolean? = null,
    val marketingConsent: Boolean? = null,
)

interface AdminUserService {
    fun list(adminId: Long, query: AdminUserQuery): Page<User>

    fun get(adminId: Long, userId: Long): User?

    fun create(adminId: Long, command: CreateAdminUserCommand): User

    fun update(adminId: Long, userId: Long, command: UpdateAdminUserCommand): User?

    fun updateBatch(
        adminId: Long,
        userIds: List<Long>,
        command: BatchUpdateAdminUsersCommand,
    ): List<User>?

    fun delete(adminId: Long, userId: Long): Long?

    fun deleteBatch(adminId: Long, userIds: List<Long>): List<Long>?

    fun purge(adminId: Long, userId: Long): Long?

    fun purgeBatch(adminId: Long, userIds: List<Long>): List<Long>?
}
