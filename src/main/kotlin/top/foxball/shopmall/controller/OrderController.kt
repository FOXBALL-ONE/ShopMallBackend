package top.foxball.shopmall.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.service.AdminOrderQuery
import top.foxball.shopmall.service.OrderLineCommand
import top.foxball.shopmall.service.OrderPageQuery
import top.foxball.shopmall.service.OrderService
import top.foxball.shopmall.service.PlaceOrderCommand
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder

@Validated
@RestController
class OrderController(
    private val orderService: OrderService,
    private val builder: ResponseBuilder,
) {
    @PostMapping("/api/orders")
    fun placeOrder(
        @AuthenticationPrincipal userId: Long,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String,
        @Valid @RequestBody request: PlaceOrderRequest,
    ): ResponseEntity<Response> {
        val command = PlaceOrderCommand(
            items = request.items.map { OrderLineCommand(it.productId, it.quantity) },
            addressId = request.addressId,
            clientMessage = request.clientMessage,
        )
        return builder.status(HttpStatus.CREATED)
            .data(orderService.placeOrder(userId, command, idempotencyKey).toResponse())
            .build()
    }

    @GetMapping("/api/orders")
    fun listCustomer(
        @AuthenticationPrincipal userId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<Response> = builder.ok()
        .data(orderService.listCustomer(userId, OrderPageQuery(page, size)).toPageResponse())
        .build()

    @GetMapping("/api/orders/{orderNo}")
    fun getCustomer(
        @AuthenticationPrincipal userId: Long,
        @PathVariable orderNo: String,
    ): ResponseEntity<Response> = builder.ok()
        .data(orderService.getCustomer(userId, orderNo).toResponse())
        .build()

    @GetMapping("/api/orders/{orderNo}/payment")
    fun getPayment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable orderNo: String,
    ): ResponseEntity<Response> = builder.ok()
        .data(orderService.getPayment(userId, orderNo).toResponse())
        .build()

    @PostMapping("/api/orders/{orderNo}/cancel")
    fun cancel(
        @AuthenticationPrincipal userId: Long,
        @PathVariable orderNo: String,
        @Valid @RequestBody request: CancelOrderRequest,
    ): ResponseEntity<Response> = builder.ok()
        .data(orderService.cancel(userId, orderNo, request.reason).toResponse())
        .build()

    @GetMapping("/api/admin/orders")
    fun listAdmin(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) @Min(1) customerId: Long?,
        @RequestParam(required = false) @Size(max = 32) orderNo: String?,
    ): ResponseEntity<Response> = builder.ok()
        .data(
            orderService.listAdmin(
                adminId,
                AdminOrderQuery(page, size, status, customerId, orderNo),
            ).toPageResponse(),
        )
        .build()

    @PostMapping("/api/admin/orders/{orderNo}/refund")
    fun refund(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable orderNo: String,
        @Valid @RequestBody request: RefundOrderRequest,
    ): ResponseEntity<Response> = builder.ok()
        .data(orderService.refund(adminId, orderNo, request.reason).toResponse())
        .build()
}
