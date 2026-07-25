package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaceOrderRequest(
    @field:NotNull
    @field:Size(min = 1, max = 10)
    @field:Valid
    val items: List<OrderLineRequest>,

    @field:NotNull
    val addressId: UUID,

    @field:Size(max = 500)
    val clientMessage: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderLineRequest(
    @field:Min(1)
    val productId: Long,

    @field:Min(1)
    @field:Max(99)
    val quantity: Int,
)

data class CancelOrderRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val reason: String,
)

data class RefundOrderRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val reason: String,
)
