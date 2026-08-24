package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.service.AdminUserQuery
import top.foxball.shopmall.service.AdminUserService
import top.foxball.shopmall.service.BatchUpdateAdminUsersCommand
import top.foxball.shopmall.service.CreateAdminUserCommand
import top.foxball.shopmall.service.UpdateAdminUserCommand
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * @folder 管理端/用户
 */
@Validated
@RestController
@RequestMapping("/admin/api/users")
class AdminUserController(
    private val adminUserService: AdminUserService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 分页查询用户
     * @param page 分页页码
     * @param pageSize 每页数量
     * @param keyword 用户名、邮箱或姓名关键词
     * @param role 用户角色
     * @param status 账号状态
     * @param enabled 是否允许登录
     */
    @GetMapping
    fun getUsers(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
        @RequestParam("keyword", required = false) @Size(max = 100) keyword: String?,
        @RequestParam("role", required = false) role: Role?,
        @RequestParam("status", required = false) status: Status?,
        @RequestParam("enabled", required = false) enabled: Boolean?,
    ): ResponseEntity<Response> {
        data class UserData(
            val id: Long,
            val email: String,
            val username: String,
            @param:JsonProperty("first_name")
            val firstName: String,
            @param:JsonProperty("last_name")
            val lastName: String,
            val role: String,
            val status: String,
            val enabled: Boolean,
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            @param:JsonProperty("last_login_at")
            val lastLoginAt: LocalDateTime?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Pagination(
            val page: Int,
            val size: Int,
            @param:JsonProperty("total_items")
            val totalItems: Long,
            @param:JsonProperty("total_pages")
            val totalPages: Int,
        )

        data class Response(
            val list: List<UserData>,
            val pagination: Pagination,
        )

        val pagedData = adminUserService.list(
            adminId,
            AdminUserQuery(
                page = page - 1,
                size = pageSize,
                keyword = keyword,
                role = role,
                status = status,
                enabled = enabled,
            ),
        )
        val list = pagedData.content.map { user ->
            UserData(
                id = requireNotNull(user.id),
                email = user.email,
                username = user.username,
                firstName = user.firstName,
                lastName = user.lastName,
                role = user.role.name,
                status = user.status.name,
                enabled = user.enabled,
                emailVerified = user.emailVerified,
                marketingConsent = user.marketingConsent,
                lastLoginAt = user.lastLoginAt,
                createdAt = user.createdAt,
                updatedAt = user.updatedAt,
            )
        }
        val rs = Response(
            list = list,
            pagination = Pagination(page, pageSize, pagedData.totalElements, pagedData.totalPages),
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取用户详情
     * @param userId 用户 ID
     */
    @GetMapping("/{user_id}")
    fun getUser(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("user_id") @Min(1) userId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val email: String,
            val username: String,
            @param:JsonProperty("first_name")
            val firstName: String,
            @param:JsonProperty("last_name")
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val birthday: LocalDate?,
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            val role: String,
            val enabled: Boolean,
            val status: String,
            @param:JsonProperty("last_login_at")
            val lastLoginAt: LocalDateTime?,
            @param:JsonProperty("last_login_ip")
            val lastLoginIp: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val user = adminUserService.get(adminId, userId) ?: return builder.notFound().message("用户不存在").build()
        val rs = Response(
            id = requireNotNull(user.id),
            email = user.email,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            phone = user.phone,
            avatar = user.avatar,
            locale = user.locale,
            currency = user.currency,
            birthday = user.birthday,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role.name,
            enabled = user.enabled,
            status = user.status.name,
            lastLoginAt = user.lastLoginAt,
            lastLoginIp = user.lastLoginIp,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 创建用户
     */
    @PostMapping
    fun createUser(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("email") @NotBlank @Email @Size(max = 100) email: String,
        @RequestParam("username") @NotBlank @Size(min = 3, max = 50) username: String,
        @RequestParam("password") @NotBlank @Size(min = 8, max = 72) password: String,
        @RequestParam("first_name", defaultValue = "") @Size(max = 50) firstName: String,
        @RequestParam("last_name", defaultValue = "") @Size(max = 50) lastName: String,
        @RequestParam("phone", required = false) @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") phone: String?,
        @RequestParam("avatar", required = false) @Size(max = 512) avatar: String?,
        @RequestParam("locale", required = false) @Size(max = 16) locale: String?,
        @RequestParam("currency", required = false) @Pattern(regexp = "^[A-Za-z]{3}$") currency: String?,
        @RequestParam("birthday", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        birthday: LocalDate?,
        @RequestParam("email_verified", defaultValue = "false") emailVerified: Boolean,
        @RequestParam("marketing_consent", defaultValue = "false") marketingConsent: Boolean,
        @RequestParam("role", defaultValue = "CUSTOMER") role: Role,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
        @RequestParam("status", defaultValue = "ACTIVE") status: Status,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val email: String,
            val username: String,
            @param:JsonProperty("first_name")
            val firstName: String,
            @param:JsonProperty("last_name")
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val birthday: LocalDate?,
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            val role: String,
            val enabled: Boolean,
            val status: String,
            @param:JsonProperty("last_login_at")
            val lastLoginAt: LocalDateTime?,
            @param:JsonProperty("last_login_ip")
            val lastLoginIp: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val user = adminUserService.create(
            adminId,
            CreateAdminUserCommand(
                email = email,
                username = username,
                password = password,
                firstName = firstName,
                lastName = lastName,
                phone = phone,
                avatar = avatar,
                locale = locale,
                currency = currency,
                birthday = birthday,
                emailVerified = emailVerified,
                marketingConsent = marketingConsent,
                role = role,
                enabled = enabled,
                status = status,
            ),
        )
        val rs = Response(
            id = requireNotNull(user.id),
            email = user.email,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            phone = user.phone,
            avatar = user.avatar,
            locale = user.locale,
            currency = user.currency,
            birthday = user.birthday,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role.name,
            enabled = user.enabled,
            status = user.status.name,
            lastLoginAt = user.lastLoginAt,
            lastLoginIp = user.lastLoginIp,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
        )
        return builder.created().data(rs).build()
    }

    /**
     * @api 更新用户
     * @param userId 用户 ID
     */
    @PutMapping("/{user_id}")
    fun updateUser(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("user_id") @Min(1) userId: Long,
        @RequestParam("email") @NotBlank @Email @Size(max = 100) email: String,
        @RequestParam("username") @NotBlank @Size(min = 3, max = 50) username: String,
        @RequestParam("first_name", defaultValue = "") @Size(max = 50) firstName: String,
        @RequestParam("last_name", defaultValue = "") @Size(max = 50) lastName: String,
        @RequestParam("phone", required = false) @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") phone: String?,
        @RequestParam("avatar", required = false) @Size(max = 512) avatar: String?,
        @RequestParam("locale", required = false) @Size(max = 16) locale: String?,
        @RequestParam("currency", required = false) @Pattern(regexp = "^[A-Za-z]{3}$") currency: String?,
        @RequestParam("birthday", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        birthday: LocalDate?,
        @RequestParam("email_verified") emailVerified: Boolean,
        @RequestParam("marketing_consent") marketingConsent: Boolean,
        @RequestParam("role") role: Role,
        @RequestParam("enabled") enabled: Boolean,
        @RequestParam("status") status: Status,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val email: String,
            val username: String,
            @param:JsonProperty("first_name")
            val firstName: String,
            @param:JsonProperty("last_name")
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val birthday: LocalDate?,
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            val role: String,
            val enabled: Boolean,
            val status: String,
            @param:JsonProperty("last_login_at")
            val lastLoginAt: LocalDateTime?,
            @param:JsonProperty("last_login_ip")
            val lastLoginIp: String?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val user = adminUserService.update(
            adminId,
            userId,
            UpdateAdminUserCommand(
                email = email,
                username = username,
                firstName = firstName,
                lastName = lastName,
                phone = phone,
                avatar = avatar,
                locale = locale,
                currency = currency,
                birthday = birthday,
                emailVerified = emailVerified,
                marketingConsent = marketingConsent,
                role = role,
                enabled = enabled,
                status = status,
            ),
        ) ?: return builder.notFound().message("用户不存在").build()
        val rs = Response(
            id = requireNotNull(user.id),
            email = user.email,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            phone = user.phone,
            avatar = user.avatar,
            locale = user.locale,
            currency = user.currency,
            birthday = user.birthday,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role.name,
            enabled = user.enabled,
            status = user.status.name,
            lastLoginAt = user.lastLoginAt,
            lastLoginIp = user.lastLoginIp,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 修改用户密码
     * @param userId 用户 ID
     * @param newPassword 新密码，长度为 8 到 72 个字符
     */
    @PutMapping("/{user_id}/password")
    fun updateUserPassword(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("user_id") @Min(1) userId: Long,
        @RequestParam("new_password") @NotBlank @Size(min = 8, max = 72) newPassword: String,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("password_changed")
            val passwordChanged: Boolean,
        )

        if (!adminUserService.updatePassword(adminId, userId, newPassword)) {
            return builder.notFound().message("用户不存在").build()
        }
        val rs = Response(userId, true)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量更新用户
     * @param ids 用户 ID 列表
     */
    @PutMapping("/batch")
    fun updateUsers(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: List<Long>,
        @RequestParam("role", required = false) role: Role?,
        @RequestParam("enabled", required = false) enabled: Boolean?,
        @RequestParam("status", required = false) status: Status?,
        @RequestParam("email_verified", required = false) emailVerified: Boolean?,
        @RequestParam("marketing_consent", required = false) marketingConsent: Boolean?,
    ): ResponseEntity<Response> {
        data class UserData(
            val id: Long,
            val username: String,
            val role: String,
            val status: String,
            val enabled: Boolean,
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(
            val list: List<UserData>,
            val updated: Int,
        )

        val users = adminUserService.updateBatch(
            adminId,
            ids,
            BatchUpdateAdminUsersCommand(
                role = role,
                enabled = enabled,
                status = status,
                emailVerified = emailVerified,
                marketingConsent = marketingConsent,
            ),
        ) ?: return builder.notFound().message("部分用户不存在").build()
        val list = users.map { user ->
            UserData(
                id = requireNotNull(user.id),
                username = user.username,
                role = user.role.name,
                status = user.status.name,
                enabled = user.enabled,
                emailVerified = user.emailVerified,
                marketingConsent = user.marketingConsent,
                updatedAt = user.updatedAt,
            )
        }
        val rs = Response(list, list.size)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除用户
     * @param userId 用户 ID
     */
    @DeleteMapping("/{user_id}")
    fun deleteUser(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("user_id") @Min(1) userId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val status: String,
            val enabled: Boolean,
        )

        val deletedUserId = adminUserService.delete(adminId, userId)
            ?: return builder.notFound().message("用户不存在").build()
        val rs = Response(deletedUserId, "DELETED", false)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量删除用户
     * @param ids 用户 ID 列表
     */
    @DeleteMapping("/batch")
    fun deleteUsers(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: List<Long>,
    ): ResponseEntity<Response> {
        data class Response(
            val ids: List<Long>,
            val deleted: Int,
        )

        val deletedIds = adminUserService.deleteBatch(adminId, ids)
            ?: return builder.notFound().message("部分用户不存在").build()
        val rs = Response(deletedIds, deletedIds.size)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 彻底删除用户
     * @param userId 必须已经处于 DELETED 状态的用户 ID
     */
    @DeleteMapping("/{user_id}/purge")
    fun purgeUser(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("user_id") @Min(1) userId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val status: String,
            val enabled: Boolean,
        )

        val purgedUserId = adminUserService.purge(adminId, userId)
            ?: return builder.notFound().message("用户不存在").build()
        val rs = Response(purgedUserId, "PURGED", false)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量彻底删除用户
     * @param ids 必须已经处于 DELETED 状态的用户 ID 列表
     */
    @DeleteMapping("/batch/purge")
    fun purgeUsers(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("ids") @Size(min = 1, max = 100) ids: List<Long>,
    ): ResponseEntity<Response> {
        data class Response(
            val ids: List<Long>,
            val purged: Int,
        )

        val purgedIds = adminUserService.purgeBatch(adminId, ids)
            ?: return builder.notFound().message("部分用户不存在").build()
        val rs = Response(purgedIds, purgedIds.size)
        return builder.ok().data(rs).build()
    }
}
