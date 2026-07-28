package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 下单时固化在订单和运单中的收货地址值对象。
 *
 * 地址从用户地址簿复制而来，后续修改用户地址不会改变已创建订单的配送信息；国家使用
 * ISO 3166-1 alpha-2 代码，电话号码使用 E.164 格式。
 */
@Embeddable
data class OrderShippingAddress(
    /** 收件人姓名。 */
    @field:NotBlank
    @field:Size(max = 100)
    @Column(name = "recipient_name", nullable = false, length = 100)
    var name: String = "",

    /** 收件人电话，使用含国家码的 E.164 格式。 */
    @field:NotBlank
    @field:Pattern(regexp = "^\\+[1-9]\\d{7,14}$")
    @Column(name = "phone", nullable = false, length = 16)
    var phone: String = "",

    /** 收货国家的 ISO 3166-1 alpha-2 代码。 */
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{2}$")
    @Column(name = "country_code", nullable = false, length = 2)
    var country: String = "",

    /** 州、省或其他一级行政区。 */
    @field:Size(max = 100)
    @Column(name = "state_or_province", length = 100)
    var stateOrProvince: String? = null,

    /** 收货城市。 */
    @field:NotBlank
    @field:Size(max = 100)
    @Column(name = "city", nullable = false, length = 100)
    var city: String = "",

    /** 区、县或其他二级行政区。 */
    @field:Size(max = 100)
    @Column(name = "district", length = 100)
    var district: String? = null,

    /** 邮政编码；没有邮编的地区可为空。 */
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

    /** 收货单位或组织名称。 */
    @field:Size(max = 100)
    @Column(name = "company", length = 100)
    var company: String? = null,

    /** 配送备注，例如门禁、代收或放置要求。 */
    @field:Size(max = 500)
    @Column(name = "delivery_instructions", length = 500)
    var deliveryInstructions: String? = null,
) {
    fun copySnapshot(): OrderShippingAddress = copy()
}
