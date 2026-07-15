package top.foxball.shopmall.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime
import java.util.UUID
import top.foxball.shopmall.shared.Response as ApiResponse

/** 用户注册、个人资料及配送地址接口。 */
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val builder: ResponseBuilder,
) {

    @PostMapping("/Register")
    fun createUser(
        @Valid @RequestBody user: User,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val id: Long,
            val email: String,
            val username: String,
            val firstName: String,
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val emailVerified: Boolean,
            val marketingConsent: Boolean,
            val role: Role,
            val enabled: Boolean,
            val status: Status,
            val lastLoginAt: LocalDateTime?,
            val createdAt: LocalDateTime?,
            val updatedAt: LocalDateTime?,
            val deliveryAddress: List<DeliveryAddressItem>,
        )

        val user = userService.createUser(user)
        val rs = Response(
            id = user.id!!,
            email = user.email,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            phone = user.phone,
            avatar = user.avatar,
            locale = user.locale,
            currency = user.currency,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role,
            enabled = user.enabled,
            status = user.status,
            lastLoginAt = user.lastLoginAt,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            deliveryAddress = user.deliveryAddress.toList(),
        )

        return builder.ok().data(rs).build()
    }

    @PostMapping("/Register/Batch")
    fun createUsers(
        @RequestBody users: List<@Valid User>,
    ): ResponseEntity<ApiResponse> {
        data class UserResponse(
            val id: Long,
            val email: String,
            val username: String,
            val firstName: String,
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val emailVerified: Boolean,
            val marketingConsent: Boolean,
            val role: Role,
            val enabled: Boolean,
            val status: Status,
            val lastLoginAt: LocalDateTime?,
            val createdAt: LocalDateTime?,
            val updatedAt: LocalDateTime?,
            val deliveryAddress: List<DeliveryAddressItem>,
        )
        data class Response(
            val users: List<UserResponse>,
        )

        val users = userService.createUsers(users)
        val rs = Response(
            users = users.map { user ->
                UserResponse(
                    id = user.id!!,
                    email = user.email,
                    username = user.username,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    phone = user.phone,
                    avatar = user.avatar,
                    locale = user.locale,
                    currency = user.currency,
                    emailVerified = user.emailVerified,
                    marketingConsent = user.marketingConsent,
                    role = user.role,
                    enabled = user.enabled,
                    status = user.status,
                    lastLoginAt = user.lastLoginAt,
                    createdAt = user.createdAt,
                    updatedAt = user.updatedAt,
                    deliveryAddress = user.deliveryAddress.toList(),
                )
            },
        )

        return builder.ok().data(rs).build()
    }

    @GetMapping("/me/delivery-addresses")
    fun getMyDeliveryAddresses(
        @AuthenticationPrincipal userId: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val deliveryAddress: List<DeliveryAddressItem>,
        )

        val deliveryAddress = userService.getDeliveryAddresses(userId)
            ?: return builder.notFound().build()
        val rs = Response(
            deliveryAddress = deliveryAddress,
        )

        return builder.ok().data(rs).build()
    }

    @GetMapping("/me/delivery-addresses/{addressId}")
    fun getMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @PathVariable addressId: UUID,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val deliveryAddress: DeliveryAddressItem,
        )

        val deliveryAddress = userService.getDeliveryAddress(userId, addressId)
            ?: return builder.notFound().build()
        val rs = Response(
            deliveryAddress = deliveryAddress,
        )

        return builder.ok().data(rs).build()
    }

    @PostMapping("/me/delivery-addresses")
    fun createMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody deliveryAddress: DeliveryAddressItem,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val deliveryAddress: DeliveryAddressItem,
        )

        val deliveryAddress = userService.createDeliveryAddress(userId, deliveryAddress)
            ?: return builder.notFound().build()
        val rs = Response(
            deliveryAddress = deliveryAddress,
        )

        return builder.ok().data(rs).build()
    }

    @PutMapping("/me/delivery-addresses/{addressId}")
    fun updateMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @PathVariable addressId: UUID,
        @Valid @RequestBody deliveryAddress: DeliveryAddressItem,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val deliveryAddress: DeliveryAddressItem,
        )

        val deliveryAddress = userService.updateDeliveryAddress(userId, addressId, deliveryAddress)
            ?: return builder.notFound().build()
        val rs = Response(
            deliveryAddress = deliveryAddress,
        )

        return builder.ok().data(rs).build()
    }

    @DeleteMapping("/me/delivery-addresses/{addressId}")
    fun deleteMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @PathVariable addressId: UUID,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val id: UUID,
            val deleted: Boolean,
        )

        val deleted = userService.deleteDeliveryAddress(userId, addressId)
            ?: return builder.notFound().build()
        if (!deleted) {
            return builder.notFound().build()
        }
        val rs = Response(
            id = addressId,
            deleted = true,
        )

        return builder.ok().data(rs).build()
    }

    @GetMapping("/{id}")
    fun getUserInfo(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val id: Long,
            val email: String,
            val username: String,
            val firstName: String,
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val emailVerified: Boolean,
            val marketingConsent: Boolean,
            val role: Role,
            val enabled: Boolean,
            val status: Status,
            val lastLoginAt: LocalDateTime?,
            val createdAt: LocalDateTime?,
            val updatedAt: LocalDateTime?,
            val deliveryAddress: List<DeliveryAddressItem>,
        )

        if (id != currentUserId) {
            return builder.forbidden().message("只能查看自己的用户信息").build()
        }
        val user = userService.getUserById(id)
            ?: return builder.notFound().build()
        val rs = Response(
            id = user.id!!,
            email = user.email,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            phone = user.phone,
            avatar = user.avatar,
            locale = user.locale,
            currency = user.currency,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role,
            enabled = user.enabled,
            status = user.status,
            lastLoginAt = user.lastLoginAt,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            deliveryAddress = user.deliveryAddress.toList(),
        )

        return builder.ok().data(rs).build()
    }

    @GetMapping("/Batch")
    fun getUsersInfo(
        @RequestParam ids: List<Long>,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<ApiResponse> {
        data class UserResponse(
            val id: Long,
            val email: String,
            val username: String,
            val firstName: String,
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val emailVerified: Boolean,
            val marketingConsent: Boolean,
            val role: Role,
            val enabled: Boolean,
            val status: Status,
            val lastLoginAt: LocalDateTime?,
            val createdAt: LocalDateTime?,
            val updatedAt: LocalDateTime?,
            val deliveryAddress: List<DeliveryAddressItem>,
        )
        data class Response(
            val users: List<UserResponse>,
        )

        if (ids.any { it != currentUserId }) {
            return builder.forbidden().message("只能查看自己的用户信息").build()
        }
        val users = userService.getUsersByIds(ids)
        val rs = Response(
            users = users.map { user ->
                UserResponse(
                    id = user.id!!,
                    email = user.email,
                    username = user.username,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    phone = user.phone,
                    avatar = user.avatar,
                    locale = user.locale,
                    currency = user.currency,
                    emailVerified = user.emailVerified,
                    marketingConsent = user.marketingConsent,
                    role = user.role,
                    enabled = user.enabled,
                    status = user.status,
                    lastLoginAt = user.lastLoginAt,
                    createdAt = user.createdAt,
                    updatedAt = user.updatedAt,
                    deliveryAddress = user.deliveryAddress.toList(),
                )
            },
        )

        return builder.ok().data(rs).build()
    }

    @PutMapping("/{id}")
    fun updateUser(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUserId: Long,
        @Valid @RequestBody user: User,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val id: Long,
            val email: String,
            val username: String,
            val firstName: String,
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val emailVerified: Boolean,
            val marketingConsent: Boolean,
            val role: Role,
            val enabled: Boolean,
            val status: Status,
            val lastLoginAt: LocalDateTime?,
            val createdAt: LocalDateTime?,
            val updatedAt: LocalDateTime?,
            val deliveryAddress: List<DeliveryAddressItem>,
        )

        if (id != currentUserId) {
            return builder.forbidden().message("只能更新自己的用户信息").build()
        }
        val existingUser = userService.getUserById(id)
            ?: return builder.notFound().build()
        existingUser.applyEditableProfileChanges(user)
        val user = userService.updateUser(existingUser)
        val rs = Response(
            id = user.id!!,
            email = user.email,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            phone = user.phone,
            avatar = user.avatar,
            locale = user.locale,
            currency = user.currency,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role,
            enabled = user.enabled,
            status = user.status,
            lastLoginAt = user.lastLoginAt,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            deliveryAddress = user.deliveryAddress.toList(),
        )

        return builder.ok().data(rs).build()
    }

    @PutMapping("/Batch")
    fun updateUsers(
        @RequestBody users: List<@Valid User>,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<ApiResponse> {
        data class UserResponse(
            val id: Long,
            val email: String,
            val username: String,
            val firstName: String,
            val lastName: String,
            val phone: String?,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val emailVerified: Boolean,
            val marketingConsent: Boolean,
            val role: Role,
            val enabled: Boolean,
            val status: Status,
            val lastLoginAt: LocalDateTime?,
            val createdAt: LocalDateTime?,
            val updatedAt: LocalDateTime?,
            val deliveryAddress: List<DeliveryAddressItem>,
        )
        data class Response(
            val users: List<UserResponse>,
        )

        if (users.any { it.id != currentUserId }) {
            return builder.forbidden().message("只能更新自己的用户信息").build()
        }
        if (users.size != 1) {
            return builder.badRequest().message("批量更新仅支持当前用户的一条记录").build()
        }
        val existingUser = userService.getUserById(currentUserId)
            ?: return builder.notFound().build()
        existingUser.applyEditableProfileChanges(users.single())
        val users = userService.updateUsers(listOf(existingUser))
        val rs = Response(
            users = users.map { user ->
                UserResponse(
                    id = user.id!!,
                    email = user.email,
                    username = user.username,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    phone = user.phone,
                    avatar = user.avatar,
                    locale = user.locale,
                    currency = user.currency,
                    emailVerified = user.emailVerified,
                    marketingConsent = user.marketingConsent,
                    role = user.role,
                    enabled = user.enabled,
                    status = user.status,
                    lastLoginAt = user.lastLoginAt,
                    createdAt = user.createdAt,
                    updatedAt = user.updatedAt,
                    deliveryAddress = user.deliveryAddress.toList(),
                )
            },
        )

        return builder.ok().data(rs).build()
    }

    @DeleteMapping("/{id}")
    fun deleteUser(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val id: Long,
            val deleted: Boolean,
        )

        if (id != currentUserId) {
            return builder.forbidden().message("只能删除自己的用户信息").build()
        }
        val deleted = userService.deleteUserById(id)
        if (!deleted) {
            return builder.notFound().build()
        }
        val rs = Response(
            id = id,
            deleted = true,
        )

        return builder.ok().data(rs).build()
    }

    @DeleteMapping("/Batch")
    fun deleteUsers(
        @RequestBody ids: List<Long>,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val ids: List<Long>,
            val deleted: Boolean,
        )

        if (ids.any { it != currentUserId }) {
            return builder.forbidden().message("只能删除自己的用户信息").build()
        }
        val deleted = userService.deleteUsersByIds(ids)
        if (!deleted) {
            return builder.notFound().build()
        }
        val rs = Response(
            ids = ids.distinct(),
            deleted = true,
        )

        return builder.ok().data(rs).build()
    }

    /** 账号凭证、权限与配送地址由各自的专用流程维护，不允许通过个人资料接口修改。 */
    private fun User.applyEditableProfileChanges(source: User) {
        firstName = source.firstName
        lastName = source.lastName
        phone = source.phone
        avatar = source.avatar
        locale = source.locale
        currency = source.currency
        marketingConsent = source.marketingConsent
    }
}
