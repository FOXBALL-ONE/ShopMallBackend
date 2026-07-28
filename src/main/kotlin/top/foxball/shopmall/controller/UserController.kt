package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Email
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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.DeliveryAddressItem
import top.foxball.shopmall.entity.jdbc.LengthUnit
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.entity.jdbc.WeightUnit
import top.foxball.shopmall.service.MailService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * @folder 用户
 */
@Validated
@RestController
class UserController(
    private val userService: UserService,
    private val mailService: MailService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 注册用户
     * @param email 邮箱
     * @param username 用户名
     * @param password 密码
     * @param verificationCode 邮箱验证码
     * @param firstName 名
     * @param lastName 姓
     * @param marketingConsent 是否同意营销信息
     */
    @PostMapping("/api/users/Register")
    fun createUser(
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        @RequestParam("email") @NotBlank @Email email: String,
        @RequestParam("username") @NotBlank @Size(min = 3, max = 50) username: String,
        @RequestParam("password") @NotBlank @Size(min = 8, max = 72) password: String,
        @RequestParam("verification_code") @NotBlank verificationCode: String,
        @RequestParam("first_name", required = false) @Size(max = 50) firstName: String?,
        @RequestParam("last_name", required = false) @Size(max = 50) lastName: String?,
        @RequestParam("marketing_consent", defaultValue = "false") marketingConsent: Boolean,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val email: String,
            val username: String,
            @param:JsonProperty("first_name")
            val firstName: String,
            @param:JsonProperty("last_name")
            val lastName: String,
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            val role: String,
            val status: String,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
        )

        mailService.verifyCode(email, verificationCode, userAgent.orEmpty(), null)
        val user = userService.createUser(
            User(
                email = email,
                username = username,
                password = password,
                firstName = firstName.orEmpty(),
                lastName = lastName.orEmpty(),
                marketingConsent = marketingConsent,
            ),
        )
        val rs = Response(
            id = requireNotNull(user.id),
            email = requireNotNull(user.email),
            username = requireNotNull(user.username),
            firstName = user.firstName,
            lastName = user.lastName,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role.name,
            status = user.status.name,
            createdAt = user.createdAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取我的配送地址列表
     */
    @GetMapping("/api/users/me/delivery-addresses")
    fun getMyDeliveryAddresses(
        @AuthenticationPrincipal userId: Long,
    ): ResponseEntity<Response> {
        data class AddressData(
            val id: UUID,
            val label: String?,
            val name: String,
            val phone: String,
            val company: String?,
            @param:JsonProperty("country_code")
            val countryCode: String,
            @param:JsonProperty("state_or_province")
            val stateOrProvince: String?,
            val city: String,
            val district: String?,
            @param:JsonProperty("postal_code")
            val postalCode: String?,
            @param:JsonProperty("address_line1")
            val addressLine1: String,
            @param:JsonProperty("address_line2")
            val addressLine2: String?,
            @param:JsonProperty("is_default")
            val isDefault: Boolean,
            @param:JsonProperty("delivery_instructions")
            val deliveryInstructions: String?,
        )

        data class Response(val list: List<AddressData>)

        val addresses = userService.getDeliveryAddresses(userId) ?: return builder.notFound().build()
        val list = addresses.map {
            AddressData(
                it.id,
                it.label,
                it.name,
                it.phone,
                it.company,
                it.country,
                it.stateOrProvince,
                it.city,
                it.district,
                it.postalCode,
                it.address1,
                it.address2,
                it.isDefault,
                it.deliveryInstructions,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取我的配送地址
     * @param addressId 地址 ID
     */
    @GetMapping("/api/users/me/delivery-addresses/{addressId}")
    fun getMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("addressId") addressId: UUID,
    ): ResponseEntity<Response> {
        data class Response(
            val id: UUID,
            val label: String?,
            val name: String,
            val phone: String,
            val company: String?,
            @param:JsonProperty("country_code")
            val countryCode: String,
            @param:JsonProperty("state_or_province")
            val stateOrProvince: String?,
            val city: String,
            val district: String?,
            @param:JsonProperty("postal_code")
            val postalCode: String?,
            @param:JsonProperty("address_line1")
            val addressLine1: String,
            @param:JsonProperty("address_line2")
            val addressLine2: String?,
            @param:JsonProperty("is_default")
            val isDefault: Boolean,
            @param:JsonProperty("delivery_instructions")
            val deliveryInstructions: String?,
        )

        val address = userService.getDeliveryAddress(userId, addressId) ?: return builder.notFound().build()
        val rs = Response(
            address.id,
            address.label,
            address.name,
            address.phone,
            address.company,
            address.country,
            address.stateOrProvince,
            address.city,
            address.district,
            address.postalCode,
            address.address1,
            address.address2,
            address.isDefault,
            address.deliveryInstructions,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 创建我的配送地址
     * @param label 地址标签
     * @param name 收件人姓名
     * @param phone 联系电话
     * @param company 公司名称
     * @param countryCode 国家代码
     * @param stateOrProvince 州或省
     * @param city 城市
     * @param district 区县
     * @param postalCode 邮政编码
     * @param addressLine1 地址第一行
     * @param addressLine2 地址第二行
     * @param isDefault 是否默认地址
     * @param deliveryInstructions 配送说明
     */
    @PostMapping("/api/users/me/delivery-addresses")
    fun createMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("label", required = false) @Size(max = 30) label: String?,
        @RequestParam("name") @NotBlank @Size(max = 100) name: String,
        @RequestParam("phone") @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") phone: String,
        @RequestParam("company", required = false) @Size(max = 100) company: String?,
        @RequestParam("country_code") @NotBlank @Pattern(regexp = "^[A-Z]{2}$") countryCode: String,
        @RequestParam("state_or_province", required = false) @Size(max = 100) stateOrProvince: String?,
        @RequestParam("city") @NotBlank @Size(max = 100) city: String,
        @RequestParam("district", required = false) @Size(max = 100) district: String?,
        @RequestParam("postal_code", required = false) @Size(max = 20) postalCode: String?,
        @RequestParam("address_line1") @NotBlank @Size(max = 255) addressLine1: String,
        @RequestParam("address_line2", required = false) @Size(max = 255) addressLine2: String?,
        @RequestParam("is_default", defaultValue = "false") isDefault: Boolean,
        @RequestParam("delivery_instructions", required = false) @Size(max = 500) deliveryInstructions: String?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: UUID,
            val label: String?,
            val name: String,
            val phone: String,
            @param:JsonProperty("country_code")
            val countryCode: String,
            val city: String,
            @param:JsonProperty("address_line1")
            val addressLine1: String,
            @param:JsonProperty("is_default")
            val isDefault: Boolean,
        )

        val address = userService.createDeliveryAddress(
            userId,
            DeliveryAddressItem(
                label = label,
                name = name,
                phone = phone,
                company = company,
                country = countryCode,
                stateOrProvince = stateOrProvince,
                city = city,
                district = district,
                postalCode = postalCode,
                address1 = addressLine1,
                address2 = addressLine2,
                isDefault = isDefault,
                deliveryInstructions = deliveryInstructions,
            ),
        ) ?: return builder.notFound().build()
        val rs = Response(
            address.id,
            address.label,
            address.name,
            address.phone,
            address.country,
            address.city,
            address.address1,
            address.isDefault,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 更新我的配送地址
     * @param addressId 地址 ID
     * @param label 地址标签
     * @param name 收件人姓名
     * @param phone 联系电话
     * @param company 公司名称
     * @param countryCode 国家代码
     * @param stateOrProvince 州或省
     * @param city 城市
     * @param district 区县
     * @param postalCode 邮政编码
     * @param addressLine1 地址第一行
     * @param addressLine2 地址第二行
     * @param isDefault 是否默认地址
     * @param deliveryInstructions 配送说明
     */
    @PutMapping("/api/users/me/delivery-addresses/{addressId}")
    fun updateMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("addressId") addressId: UUID,
        @RequestParam("label", required = false) @Size(max = 30) label: String?,
        @RequestParam("name") @NotBlank @Size(max = 100) name: String,
        @RequestParam("phone") @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") phone: String,
        @RequestParam("company", required = false) @Size(max = 100) company: String?,
        @RequestParam("country_code") @NotBlank @Pattern(regexp = "^[A-Z]{2}$") countryCode: String,
        @RequestParam("state_or_province", required = false) @Size(max = 100) stateOrProvince: String?,
        @RequestParam("city") @NotBlank @Size(max = 100) city: String,
        @RequestParam("district", required = false) @Size(max = 100) district: String?,
        @RequestParam("postal_code", required = false) @Size(max = 20) postalCode: String?,
        @RequestParam("address_line1") @NotBlank @Size(max = 255) addressLine1: String,
        @RequestParam("address_line2", required = false) @Size(max = 255) addressLine2: String?,
        @RequestParam("is_default") isDefault: Boolean,
        @RequestParam("delivery_instructions", required = false) @Size(max = 500) deliveryInstructions: String?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: UUID,
            val label: String?,
            val name: String,
            val phone: String,
            @param:JsonProperty("country_code")
            val countryCode: String,
            val city: String,
            @param:JsonProperty("address_line1")
            val addressLine1: String,
            @param:JsonProperty("is_default")
            val isDefault: Boolean,
        )

        val address = userService.updateDeliveryAddress(
            userId,
            addressId,
            DeliveryAddressItem(
                id = addressId,
                label = label,
                name = name,
                phone = phone,
                company = company,
                country = countryCode,
                stateOrProvince = stateOrProvince,
                city = city,
                district = district,
                postalCode = postalCode,
                address1 = addressLine1,
                address2 = addressLine2,
                isDefault = isDefault,
                deliveryInstructions = deliveryInstructions,
            ),
        ) ?: return builder.notFound().build()
        val rs = Response(
            address.id,
            address.label,
            address.name,
            address.phone,
            address.country,
            address.city,
            address.address1,
            address.isDefault,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除我的配送地址
     * @param addressId 地址 ID
     */
    @DeleteMapping("/api/users/me/delivery-addresses/{addressId}")
    fun deleteMyDeliveryAddress(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("addressId") addressId: UUID,
    ): ResponseEntity<Response> {
        data class Response(val id: UUID, val deleted: Boolean)

        val deleted = userService.deleteDeliveryAddress(userId, addressId) ?: return builder.notFound().build()
        if (!deleted) return builder.notFound().build()
        val rs = Response(addressId, true)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取用户资料
     * @param id 用户 ID
     */
    @GetMapping("/api/users/{id}")
    fun getUserInfo(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal currentUserId: Long,
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
            val bust: BigDecimal?,
            val waist: BigDecimal?,
            val hip: BigDecimal?,
            val torso: BigDecimal?,
            @param:JsonProperty("bra_size")
            val braSize: String?,
            @param:JsonProperty("cup_size")
            val cupSize: String?,
            val weight: BigDecimal?,
            @param:JsonProperty("weight_unit")
            val weightUnit: String?,
            val height: BigDecimal?,
            @param:JsonProperty("length_unit")
            val lengthUnit: String?,
            @param:JsonProperty("email_verified")
            val emailVerified: Boolean,
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            val role: String,
            val enabled: Boolean,
            val status: String,
            @param:JsonProperty("last_login_at")
            val lastLoginAt: LocalDateTime?,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        if (id != currentUserId) return builder.forbidden().message("只能查看自己的用户信息").build()
        val user = userService.getUserById(id) ?: return builder.notFound().build()
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
            bust = user.bust,
            waist = user.waist,
            hip = user.hip,
            torso = user.torso,
            braSize = user.braSize,
            cupSize = user.cupSize,
            weight = user.weight,
            weightUnit = user.weightUnit?.name,
            height = user.height,
            lengthUnit = user.lengthUnit?.name,
            emailVerified = user.emailVerified,
            marketingConsent = user.marketingConsent,
            role = user.role.name,
            enabled = user.enabled,
            status = user.status.name,
            lastLoginAt = user.lastLoginAt,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量获取用户资料
     * @param ids 用户 ID 列表
     */
    @GetMapping("/api/users/Batch")
    fun getUsersInfo(
        @RequestParam("ids") ids: List<Long>,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<Response> {
        data class UserData(
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
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            val role: String,
            val status: String,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<UserData>)

        if (ids.any { it != currentUserId }) {
            return builder.forbidden().message("只能查看自己的用户信息").build()
        }
        val list = userService.getUsersByIds(ids).map {
            UserData(
                id = requireNotNull(it.id),
                email = it.email,
                username = it.username,
                firstName = it.firstName,
                lastName = it.lastName,
                phone = it.phone,
                avatar = it.avatar,
                locale = it.locale,
                currency = it.currency,
                birthday = it.birthday,
                marketingConsent = it.marketingConsent,
                role = it.role.name,
                status = it.status.name,
                updatedAt = it.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 更新用户资料
     * @param id 用户 ID
     * @param firstName 名
     * @param lastName 姓
     * @param phone 电话
     * @param avatar 头像
     * @param locale 语言区域
     * @param currency 币种
     * @param birthday 生日
     * @param bust 胸围
     * @param waist 腰围
     * @param hip 臀围
     * @param torso 躯干长度
     * @param braSize 文胸尺码
     * @param cupSize 罩杯
     * @param weight 体重
     * @param weightUnit 体重单位
     * @param height 身高
     * @param lengthUnit 长度单位
     * @param marketingConsent 是否同意营销信息
     */
    @PutMapping("/api/users/{id}")
    fun updateUser(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal currentUserId: Long,
        @RequestParam("first_name", defaultValue = "") @Size(max = 50) firstName: String,
        @RequestParam("last_name", defaultValue = "") @Size(max = 50) lastName: String,
        @RequestParam("phone", required = false) @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") phone: String?,
        @RequestParam("avatar", required = false) @Size(max = 512) avatar: String?,
        @RequestParam("locale", required = false) @Size(max = 16) locale: String?,
        @RequestParam("currency", required = false) @Size(max = 3) currency: String?,
        @RequestParam("birthday", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) birthday: LocalDate?,
        @RequestParam("bust", required = false) @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) bust: BigDecimal?,
        @RequestParam("waist", required = false) @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) waist: BigDecimal?,
        @RequestParam("hip", required = false) @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) hip: BigDecimal?,
        @RequestParam("torso", required = false) @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) torso: BigDecimal?,
        @RequestParam("bra_size", required = false) @Size(max = 12) braSize: String?,
        @RequestParam("cup_size", required = false) @Size(max = 8) cupSize: String?,
        @RequestParam("weight", required = false) @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) weight: BigDecimal?,
        @RequestParam("weight_unit", required = false) weightUnit: WeightUnit?,
        @RequestParam("height", required = false) @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) height: BigDecimal?,
        @RequestParam("length_unit", required = false) lengthUnit: LengthUnit?,
        @RequestParam("marketing_consent", defaultValue = "false") marketingConsent: Boolean,
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
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        if (id != currentUserId) return builder.forbidden().message("只能更新自己的用户信息").build()
        val user = userService.getUserById(id) ?: return builder.notFound().build()
        user.firstName = firstName
        user.lastName = lastName
        user.phone = phone
        user.avatar = avatar
        user.locale = locale
        user.currency = currency
        user.birthday = birthday
        user.bust = bust
        user.waist = waist
        user.hip = hip
        user.torso = torso
        user.braSize = braSize
        user.cupSize = cupSize
        user.weight = weight
        user.weightUnit = weightUnit
        user.height = height
        user.lengthUnit = lengthUnit
        user.marketingConsent = marketingConsent
        val updated = userService.updateUser(user)
        val rs = Response(
            id = requireNotNull(updated.id),
            email = updated.email,
            username = updated.username,
            firstName = updated.firstName,
            lastName = updated.lastName,
            phone = updated.phone,
            avatar = updated.avatar,
            locale = updated.locale,
            currency = updated.currency,
            birthday = updated.birthday,
            marketingConsent = updated.marketingConsent,
            updatedAt = updated.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量更新当前用户资料
     * @param ids 用户 ID 列表
     * @param firstName 名
     * @param lastName 姓
     * @param phone 电话
     * @param avatar 头像
     * @param locale 语言区域
     * @param currency 币种
     * @param marketingConsent 是否同意营销信息
     */
    @PutMapping("/api/users/Batch")
    fun updateUsers(
        @AuthenticationPrincipal currentUserId: Long,
        @RequestParam("ids") ids: List<Long>,
        @RequestParam("first_name", defaultValue = "") @Size(max = 50) firstName: String,
        @RequestParam("last_name", defaultValue = "") @Size(max = 50) lastName: String,
        @RequestParam("phone", required = false) @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") phone: String?,
        @RequestParam("avatar", required = false) @Size(max = 512) avatar: String?,
        @RequestParam("locale", required = false) @Size(max = 16) locale: String?,
        @RequestParam("currency", required = false) @Size(max = 3) currency: String?,
        @RequestParam("marketing_consent", defaultValue = "false") marketingConsent: Boolean,
    ): ResponseEntity<Response> {
        data class UserData(
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
            @param:JsonProperty("marketing_consent")
            val marketingConsent: Boolean,
        )

        data class Response(val list: List<UserData>)

        if (ids.size != 1 || ids.singleOrNull() != currentUserId) {
            return builder.forbidden().message("批量更新仅支持当前用户的一条记录").build()
        }
        val user = userService.getUserById(currentUserId) ?: return builder.notFound().build()
        user.firstName = firstName
        user.lastName = lastName
        user.phone = phone
        user.avatar = avatar
        user.locale = locale
        user.currency = currency
        user.marketingConsent = marketingConsent
        val list = userService.updateUsers(listOf(user)).map {
            UserData(
                requireNotNull(it.id),
                it.email,
                it.username,
                it.firstName,
                it.lastName,
                it.phone,
                it.avatar,
                it.locale,
                it.currency,
                it.marketingConsent,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除用户
     * @param id 用户 ID
     */
    @DeleteMapping("/api/users/{id}")
    fun deleteUser(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        if (id != currentUserId) return builder.forbidden().message("只能删除自己的用户信息").build()
        if (!userService.deleteUserById(id)) return builder.notFound().build()
        val rs = Response(id, true)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 批量删除用户
     * @param ids 用户 ID 列表
     */
    @DeleteMapping("/api/users/Batch")
    fun deleteUsers(
        @RequestParam("ids") ids: List<Long>,
        @AuthenticationPrincipal currentUserId: Long,
    ): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val deleted: Boolean)

        if (ids.any { it != currentUserId }) {
            return builder.forbidden().message("只能删除自己的用户信息").build()
        }
        if (!userService.deleteUsersByIds(ids)) return builder.notFound().build()
        val rs = Response(ids.distinct(), true)
        return builder.ok().data(rs).build()
    }
}
