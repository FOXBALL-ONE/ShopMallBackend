package top.foxball.shopmall.controller

import com.stripe.StripeClient
import com.stripe.exception.SignatureVerificationException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.handler.WebhookPayloadTooLargeException
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.service.payMent.stripe.StripeProperties
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.io.ByteArrayOutputStream

/** Stripe Checkout 回调的独立入口；不与订单业务 API 共用路由。 */
@RestController
class StripeWebhookController(
    private val stripeClient: StripeClient,
    private val stripeProperties: StripeProperties,
    private val paymentService: OrderPaymentService,
    private val builder: ResponseBuilder,
) {
    @PostMapping(StripeProperties.WEBHOOK_PATH)
    fun webhook(request: HttpServletRequest): ResponseEntity<Response> {
        val signature = request.getHeader("Stripe-Signature")
            ?: return builder.badRequest().message("Missing Stripe signature").build()
        val payload = readLimited(request).toString(Charsets.UTF_8)
        val event = try {
            stripeClient.constructEvent(payload, signature, stripeProperties.webhookSecret)
        } catch (_: SignatureVerificationException) {
            return builder.badRequest().message("Invalid Stripe signature").build()
        }
        paymentService.handleWebhookEvent(event)
        return builder.ok().build()
    }

    private fun readLimited(request: HttpServletRequest): ByteArray {
        val limit = stripeProperties.webhookMaxBodyBytes
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        request.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > limit - output.size()) throw WebhookPayloadTooLargeException()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
