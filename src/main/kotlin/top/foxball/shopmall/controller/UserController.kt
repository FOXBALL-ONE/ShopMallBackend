package top.foxball.shopmall.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.service.MailService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import top.foxball.shopmall.shared.Response as ApiResponse

/** 用户资料的对外响应模型，明确排除密码和内部登录 IP。 */
private data class UserProfileResponse(
    val id: Long,
    val email: String,
    val username: String,
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val avatar: String?,
    val locale: String?,
    val currency: String?,
    val birthday: LocalDate?,
    val bust: BigDecimal?,
    val waist: BigDecimal?,
    val hip: BigDecimal?,
    val torso: BigDecimal?,
    val braSize: String?,
    val cupSize: String?,
    val weight: BigDecimal?,
    val weightUnit: top.foxball.shopmall.entity.jdbc.WeightUnit?,
    val height: BigDecimal?,
    val lengthUnit: top.foxball.shopmall.entity.jdbc.LengthUnit?,
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

/** 将持久化用户映射为对外资料，避免多个接口遗漏新字段或泄露密码。 */
private fun User.toProfileResponse(): UserProfileResponse =
    UserProfileResponse(
        id = requireNotNull(id),
        email = email,
        username = username,
        firstName = firstName,
        lastName = lastName,
        phone = phone,
        avatar = avatar,
        locale = locale,
        currency = currency,
        birthday = birthday,
        bust = bust,
        waist = waist,
        hip = hip,
        torso = torso,
        braSize = braSize,
        cupSize = cupSize,
        weight = weight,
        weightUnit = weightUnit,
        height = height,
        lengthUnit = lengthUnit,
        emailVerified = emailVerified,
        marketingConsent = marketingConsent,
        role = role,
        enabled = enabled,
        status = status,
        lastLoginAt = lastLoginAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deliveryAddress = deliveryAddress.toList(),
    )

/** 用户注册、个人资料及配送地址接口。 */
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val mailService: MailService,
    private val builder: ResponseBuilder,
) {

    /** 注册：校验邮箱验证码后创建用户；请求体仅含注册可填字段，权限与凭证由服务端控制。 */
    @PostMapping("/Register")
    fun createUser(
        @RequestHeader(value = "User-Agent", required = false) userAgent: String?,
        @Valid @RequestBody request: RegisterRequest,
    ): ResponseEntity<ApiResponse> {
        mailService.verifyCode(request.email, request.verificationCode, userAgent.orEmpty(), null)
        val saved = userService.createUser(request.toUser())
        val rs = saved.toProfileResponse()

        return builder.ok().data(rs).build()
    }

    /**
     * 注册请求；仅暴露注册可填字段。账号角色、状态、邮箱验证标记等由服务端默认值控制，
     * 避免 [User] 实体其余字段被批量赋值（如越权注册为管理员）。
     */
    data class RegisterRequest(
        @field:NotBlank
        @field:Email
        val email: String,

        @field:NotBlank
        @field:Size(min = 3, max = 50)
        val username: String,

        /** 明文密码，由 UserService 加密后持久化；长度限制与 Argon2 输入一致。 */
        @field:NotBlank
        @field:Size(min = 8, max = 72)
        val password: String,

        @field:NotBlank
        val verificationCode: String,

        @field:Size(max = 50)
        val firstName: String? = null,

        @field:Size(max = 50)
        val lastName: String? = null,

        val marketingConsent: Boolean = false,
    ) {
        /** 映射为待持久化的 [User]；未出现的字段沿用实体安全默认值（CUSTOMER/ACTIVE/未验证等）。 */
        fun toUser(): User = User(
            email = email,
            username = username,
            password = password,
            firstName = firstName ?: "",
            lastName = lastName ?: "",
            marketingConsent = marketingConsent,
        )
    }

    @PostMapping("/Register/Batch")
    fun createUsers(
        @RequestBody users: List<@Valid User>,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val users: List<UserProfileResponse>,
        )

        val users = userService.createUsers(users)
        val rs = Response(
            users = users.map { it.toProfileResponse() },
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
        if (id != currentUserId) {
            return builder.forbidden().message("只能查看自己的用户信息").build()
        }
        val user = userService.getUserById(id)
            ?: return builder.notFound().build()
        val rs = user.toProfileResponse()

        return builder.ok().data(rs).build()
    }

    @GetMapping("/Batch")
    fun getUsersInfo(
        @RequestParam ids: List<Long>,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val users: List<UserProfileResponse>,
        )

        if (ids.any { it != currentUserId }) {
            return builder.forbidden().message("只能查看自己的用户信息").build()
        }
        val users = userService.getUsersByIds(ids)
        val rs = Response(
            users = users.map { it.toProfileResponse() },
        )

        return builder.ok().data(rs).build()
    }

    @PutMapping("/{id}")
    fun updateUser(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUserId: Long,
        @Valid @RequestBody user: User,
    ): ResponseEntity<ApiResponse> {
        if (id != currentUserId) {
            return builder.forbidden().message("只能更新自己的用户信息").build()
        }
        val existingUser = userService.getUserById(id)
            ?: return builder.notFound().build()
        existingUser.applyEditableProfileChanges(user)
        val user = userService.updateUser(existingUser)
        val rs = user.toProfileResponse()

        return builder.ok().data(rs).build()
    }

    @PutMapping("/Batch")
    fun updateUsers(
        @RequestBody users: List<@Valid User>,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(
            val users: List<UserProfileResponse>,
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
            users = users.map { it.toProfileResponse() },
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
        birthday = source.birthday
        bust = source.bust
        waist = source.waist
        hip = source.hip
        torso = source.torso
        braSize = source.braSize
        cupSize = source.cupSize
        weight = source.weight
        weightUnit = source.weightUnit
        height = source.height
        lengthUnit = source.lengthUnit
        marketingConsent = source.marketingConsent
    }
}
