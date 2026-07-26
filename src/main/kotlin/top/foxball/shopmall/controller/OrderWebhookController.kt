package top.foxball.shopmall.controller

import com.stripe.StripeClient
import com.stripe.exception.SignatureVerificationException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.shared.StripeProperties
import java.nio.charset.StandardCharsets

/**
 * @folder 订单/Webhook
 */
@RestController
class OrderWebhookController(
    private val stripeClient: StripeClient,
    private val stripeProperties: StripeProperties,
    private val paymentService: OrderPaymentService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 接收 Stripe 支付回调
     */
    @PostMapping("/api/orders/webhook")
    fun webhook(request: HttpServletRequest): ResponseEntity<Response> {
        val signature = request.getHeader("Stripe-Signature")
            ?: return builder.badRequest().message("Missing Stripe signature").build()
        val payload = request.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val event = try {
            stripeClient.constructEvent(payload, signature, stripeProperties.webhookSecret)
        } catch (_: SignatureVerificationException) {
            return builder.badRequest().message("Invalid Stripe signature").build()
        }
        paymentService.handleWebhookEvent(event)
        return builder.ok().build()
    }
}
