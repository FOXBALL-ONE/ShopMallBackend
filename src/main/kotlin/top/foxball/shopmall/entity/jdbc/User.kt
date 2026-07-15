package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PastOrPresent
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import lombok.Data
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 用户实体，对应 users 表。
 *
 * 面向海外独立小站：以邮箱为核心联系方式与通知通道，姓名按西方习惯拆分 [firstName]/[lastName]，
 * 电话存 E.164（含国家码）以适配国际拨号，并保留 [locale]/[currency] 用于多语言与多币种展示。
 *
 * 时间戳由 Hibernate 自动维护：[createdAt] 仅在插入时写入且不可更新，[updatedAt] 每次写操作时刷新。
 * 登录准入由 [enabled] 控制（[top.foxball.shopmall.service.AuthService] 据此拦截登录）；
 * [status] 描述账号生命周期（含软删除），二者职责不同，不要混用。
 */
@Data
@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 注册邮箱，全局唯一，兼作通知与找回账号的主通道。 */
    @Column(nullable = false, unique = true, length = 100)
    var email: String = "",

    /** 登录用户名，全局唯一（当前登录凭据；如需改为邮箱登录需同步调整 AuthService）。 */
    @Column(nullable = false, unique = true, length = 50)
    var username: String = "",

    /** 加密后的密码（BCrypt），禁止明文存储。 */
    @Column(nullable = false, length = 255)
    var password: String = "",

    /** 名（first name），下单/发货时与 [lastName] 一并作为收件人。 */
    @Column(length = 50)
    var firstName: String = "",

    /** 姓（last name）。 */
    @Column(length = 50)
    var lastName: String = "",

    /** 联系电话，E.164 格式含国家码（如 +14155550123），最长 15 位数字加前缀。 */
    @Column(length = 20)
    var phone: String? = null,

    /** 头像 URL。 */
    @Column(length = 512)
    var avatar: String? = null,

    /** 偏好语言/区域（BCP 47，如 en-US、fr-FR），用于邮件与界面本地化，为空时回退站点默认。 */
    @Column(length = 16)
    var locale: String? = null,

    /** 偏好展示币种（ISO 4217，如 USD、EUR），为空时回退站点默认。 */
    @Column(length = 3)
    var currency: String? = "USD",

    /** 出生日期；采用 ISO-8601 `yyyy-MM-dd`，不包含时区或具体出生时间。 */
    @field:PastOrPresent(message = "生日不能晚于今天")
    @Column
    var birthday: LocalDate? = null,

    /** 胸围；与 [waist]、[hip]、[torso]、[height] 共用 [lengthUnit]。 */
    @field:DecimalMin("0.01")
    @field:Digits(integer = 4, fraction = 2)
    @Column(precision = 6, scale = 2)
    var bust: BigDecimal? = null,

    /** 腰围。 */
    @field:DecimalMin("0.01")
    @field:Digits(integer = 4, fraction = 2)
    @Column(precision = 6, scale = 2)
    var waist: BigDecimal? = null,

    /** 臀围。 */
    @field:DecimalMin("0.01")
    @field:Digits(integer = 4, fraction = 2)
    @Column(precision = 6, scale = 2)
    var hip: BigDecimal? = null,

    /** 躯干长度。 */
    @field:DecimalMin("0.01")
    @field:Digits(integer = 4, fraction = 2)
    @Column(precision = 6, scale = 2)
    var torso: BigDecimal? = null,

    /** 文胸下胸围/罩杯带围，例如 32、34 或 75。 */
    @field:Size(max = 12)
    @Column(length = 12)
    var braSize: String? = null,

    /** 文胸罩杯，例如 A、B、C、D 或 DD。 */
    @field:Size(max = 8)
    @Column(length = 8)
    var cupSize: String? = null,

    /** 体重，单位由 [weightUnit] 指定。 */
    @field:DecimalMin("0.01")
    @field:Digits(integer = 4, fraction = 2)
    @Column(precision = 6, scale = 2)
    var weight: BigDecimal? = null,

    /** 体重单位；与长度单位分离，避免将体重误标为英寸或厘米。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    var weightUnit: WeightUnit? = null,

    /** 身高；单位由 [lengthUnit] 指定。 */
    @field:DecimalMin("0.01")
    @field:Digits(integer = 4, fraction = 2)
    @Column(precision = 6, scale = 2)
    var height: BigDecimal? = null,

    /** 胸围、腰围、臀围、躯干和身高统一使用的长度单位，仅允许 INCH 或 CM。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 5)
    var lengthUnit: LengthUnit? = null,

    /** 是否已通过邮箱验证，未验证前限制下单等敏感操作。 */
    @Column(nullable = false)
    var emailVerified: Boolean = false,

    /** 是否同意接收营销邮件（GDPR/CCPA 合规，默认不同意，需用户主动勾选）。 */
    @Column(nullable = false)
    var marketingConsent: Boolean = false,

    /** 账号角色，区分消费者与管理员。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: Role = Role.CUSTOMER,

    /** 是否允许登录，false 时登录直接被拒（如被封禁、未激活）。 */
    @Column(nullable = false)
    var enabled: Boolean = true,

    /** 账号生命周期状态；[Status.DELETED] 表示软删除。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: Status = Status.ACTIVE,

    /** 最近一次登录时间，未登录过为 null。 */
    @Column
    var lastLoginAt: LocalDateTime? = null,

    /** 最近一次登录 IP（兼容 IPv6），用于异常登录排查。 */
    @Column(length = 45)
    var lastLoginIp: String? = null,

    /** 创建时间，由 [CreationTimestamp] 在插入时自动写入，不可更新。 */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    /** 最后更新时间，由 [UpdateTimestamp] 在每次写操作时自动刷新。 */
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null,

    /**
     * 用户保存的配送地址，按列表顺序展示；最多保存 20 个。
     *
     * 地址作为随用户生命周期维护的值对象，存储在 user_delivery_addresses 集合表中，
     * 而不是序列化到 users 表的 varchar 字段。
     */
    @field:Valid
    @field:Size(max = 20)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "user_delivery_addresses",
        joinColumns = [JoinColumn(name = "user_id", nullable = false)],
        indexes = [Index(name = "idx_delivery_address_user", columnList = "user_id")],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_delivery_address_user_address",
                columnNames = ["user_id", "address_id"],
            ),
        ],
    )
    @OrderColumn(name = "sort_order")
    var deliveryAddress: MutableList<DeliveryAddressItem> = mutableListOf(),
) {
    /** 填写任一长度数据时必须选择 INCH 或 CM，避免持久化不可解释的数值。 */
    @get:JsonIgnore
    @get:AssertTrue(message = "填写身体尺寸时必须选择长度单位")
    val isLengthUnitValid: Boolean
        get() = !hasLengthMeasurements() || lengthUnit != null

    /** 填写体重时必须给出 KG 或 LB。 */
    @get:JsonIgnore
    @get:AssertTrue(message = "填写体重时必须选择体重单位")
    val isWeightUnitValid: Boolean
        get() = weight == null || weightUnit != null

    private fun hasLengthMeasurements(): Boolean =
        bust != null || waist != null || hip != null || torso != null || height != null
}

/** 配送地址；国家使用 ISO 3166-1 alpha-2 代码，电话使用 E.164 格式。 */
@Embeddable
data class DeliveryAddressItem(
    /** 地址唯一标识；由服务端生成，用于当前用户对单条地址进行增删改查。 */
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "address_id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    /** 用户可见的地址标签，如 Home、Office。 */
    @field:Size(max = 30)
    @Column(name = "label", length = 30)
    var label: String? = null,

    /** 收件人姓名。 */
    @field:NotBlank
    @field:Size(max = 100)
    @Column(name = "recipient_name", nullable = false, length = 100)
    var name: String = "",

    /** 收件人联系电话，包含国家码，例如 +14155550123。 */
    @field:NotBlank
    @field:Pattern(regexp = "^\\+[1-9]\\d{7,14}$")
    @Column(name = "phone", nullable = false, length = 16)
    var phone: String = "",

    /** 公司或组织名称，企业地址可填写。 */
    @field:Size(max = 100)
    @Column(name = "company", length = 100)
    var company: String? = null,

    /** ISO 3166-1 alpha-2 国家代码，例如 US、CN。 */
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{2}$")
    @Column(name = "country_code", nullable = false, length = 2)
    var country: String = "",

    /** 州、省或其他一级行政区。 */
    @field:Size(max = 100)
    @Column(name = "state_or_province", length = 100)
    var stateOrProvince: String? = null,

    /** 城市。 */
    @field:NotBlank
    @field:Size(max = 100)
    @Column(name = "city", nullable = false, length = 100)
    var city: String = "",

    /** 区、县或其他二级行政区。 */
    @field:Size(max = 100)
    @Column(name = "district", length = 100)
    var district: String? = null,

    /** 邮政编码；部分国家或地区没有邮编，因此允许为空。 */
    @field:Size(max = 20)
    @Column(name = "postal_code", length = 20)
    var postalCode: String? = null,

    /** 街道、门牌号等主要地址信息。 */
    @field:NotBlank
    @field:Size(max = 255)
    @Column(name = "address_line1", nullable = false, length = 255)
    var address1: String = "",

    /** 公寓、楼层、套间或单元号等补充地址信息。 */
    @field:Size(max = 255)
    @Column(name = "address_line2", length = 255)
    var address2: String? = null,

    /** 是否为默认配送地址；每个用户只能有一个默认地址，由业务层保证。 */
    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    /** 配送备注，例如门禁、前台代收或放置位置。 */
    @field:Size(max = 500)
    @Column(name = "delivery_instructions", length = 500)
    var deliveryInstructions: String? = null,
)

/** 账号生命周期。 */
enum class Status {
    ACTIVE, INACTIVE, DELETED
}

/** 账号角色。 */
enum class Role {
    CUSTOMER, ADMIN
}

/** 身体长度与围度单位。 */
enum class LengthUnit {
    INCH, CM
}

/** 体重单位。 */
enum class WeightUnit {
    KG, LB
}
