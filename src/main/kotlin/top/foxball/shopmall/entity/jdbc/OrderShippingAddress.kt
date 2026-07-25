package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Embeddable
data class OrderShippingAddress(
    @field:NotBlank
    @field:Size(max = 100)
    @Column(name = "recipient_name", nullable = false, length = 100)
    var name: String = "",

    @field:NotBlank
    @field:Pattern(regexp = "^\\+[1-9]\\d{7,14}$")
    @Column(name = "phone", nullable = false, length = 16)
    var phone: String = "",

    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{2}$")
    @Column(name = "country_code", nullable = false, length = 2)
    var country: String = "",

    @field:Size(max = 100)
    @Column(name = "state_or_province", length = 100)
    var stateOrProvince: String? = null,

    @field:NotBlank
    @field:Size(max = 100)
    @Column(name = "city", nullable = false, length = 100)
    var city: String = "",

    @field:Size(max = 100)
    @Column(name = "district", length = 100)
    var district: String? = null,

    @field:Size(max = 20)
    @Column(name = "postal_code", length = 20)
    var postalCode: String? = null,

    @field:NotBlank
    @field:Size(max = 255)
    @Column(name = "address_line1", nullable = false, length = 255)
    var address1: String = "",

    @field:Size(max = 255)
    @Column(name = "address_line2", length = 255)
    var address2: String? = null,

    @field:Size(max = 100)
    @Column(name = "company", length = 100)
    var company: String? = null,

    @field:Size(max = 500)
    @Column(name = "delivery_instructions", length = 500)
    var deliveryInstructions: String? = null,
) {
    fun copySnapshot(): OrderShippingAddress = copy()
}
