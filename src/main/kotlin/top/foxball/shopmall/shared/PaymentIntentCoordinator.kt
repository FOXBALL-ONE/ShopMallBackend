package top.foxball.shopmall.shared

import com.stripe.StripeClient
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentCancelParams
import com.stripe.param.RefundCreateParams
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class PaymentIntentCoordinator(
    private val stripeClient: StripeClient,
    private val redis: StringRedisTemplate,
) {
    fun cancelOrRefund(paymentIntentId: String, idempotencyKey: String) {
        withLock(paymentIntentId) {
            val paymentIntent = stripeClient.paymentIntents().retrieve(paymentIntentId)
            if (paymentIntent.status == "succeeded") {
                stripeClient.refunds().create(
                    RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build(),
                )
            } else if (paymentIntent.status != "canceled") {
                stripeClient.paymentIntents().cancel(
                    paymentIntentId,
                    PaymentIntentCancelParams.builder().build(),
                )
            }
        }
    }

    fun refund(paymentIntentId: String, idempotencyKey: String) {
        withLock(paymentIntentId) {
            stripeClient.refunds().create(
                RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build(),
            )
        }
    }

    private fun withLock(paymentIntentId: String, action: () -> Unit) {
        val key = "lock:pi:$paymentIntentId"
        val token = UUID.randomUUID().toString()
        val acquired = redis.opsForValue().setIfAbsent(key, token, LOCK_TTL) == true
        if (!acquired) {
            logger.warn("Stripe operation skipped because payment intent {} is already locked", paymentIntentId)
            return
        }
        try {
            action()
        } finally {
            redis.execute(releaseScript, listOf(key), token)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PaymentIntentCoordinator::class.java)
        val LOCK_TTL: Duration = Duration.ofSeconds(30)
        val releaseScript = DefaultRedisScript(
            """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
