package top.foxball.shopmall.shared

import com.stripe.StripeClient
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentCancelParams
import com.stripe.param.RefundCreateParams
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 串行协调同一 Stripe 支付资源的取消与退款补偿。
 *
 * 分布式锁只保护应用侧并发；退款仍通过 Stripe 幂等键保证请求重试时不会重复扣款。
 */
@Component
class PaymentIntentCoordinator(
    private val stripeClient: StripeClient,
    private val redis: StringRedisTemplate,
) {
    /**
     * 处理非 Checkout PaymentIntent：已支付时退款，未取消的未完成支付则直接取消。
     */
    fun cancelOrRefund(paymentIntentId: String, idempotencyKey: String) {
        withLock(paymentIntentId) {
            val paymentIntent = stripeClient.v1().paymentIntents().retrieve(paymentIntentId)
            if (paymentIntent.status == "succeeded") {
                stripeClient.v1().refunds().create(
                    RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build(),
                )
            } else if (paymentIntent.status != "canceled") {
                stripeClient.v1().paymentIntents().cancel(
                    paymentIntentId,
                    PaymentIntentCancelParams.builder().build(),
                )
            }
        }
    }

    /**
     * Checkout 订单必须优先使 Session 失效，不能直接取消其 PaymentIntent。
     * 若支付已经完成，则改为创建退款；未完成支付在 Session 过期后由 Stripe 自行终止。
     */
    fun cancelOrRefund(
        checkoutSessionId: String?,
        paymentIntentId: String?,
        idempotencyKey: String,
    ) {
        val lockId = checkoutSessionId ?: paymentIntentId ?: return
        withLock(lockId) {
            val resolvedPaymentIntentId = checkoutSessionId?.let { sessionId ->
                val session = stripeClient.v1().checkout().sessions().retrieve(sessionId)
                val finalSession = if (session.status == "open") {
                    stripeClient.v1().checkout().sessions().expire(sessionId)
                } else {
                    session
                }
                finalSession.paymentIntent ?: paymentIntentId
            } ?: paymentIntentId
            val paymentIntent = resolvedPaymentIntentId?.let {
                stripeClient.v1().paymentIntents().retrieve(it)
            } ?: return@withLock
            if (paymentIntent.status == "succeeded") {
                stripeClient.v1().refunds().create(
                    RefundCreateParams.builder().setPaymentIntent(paymentIntent.id).build(),
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build(),
                )
            } else if (checkoutSessionId == null && paymentIntent.status != "canceled") {
                stripeClient.v1().paymentIntents().cancel(
                    paymentIntent.id,
                    PaymentIntentCancelParams.builder().build(),
                )
            }
        }
    }

    /** 为已成功的 PaymentIntent 创建退款；调用方须为同一业务退款复用同一个幂等键。 */
    fun refund(paymentIntentId: String, idempotencyKey: String) {
        withLock(paymentIntentId) {
            stripeClient.v1().refunds().create(
                RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build(),
            )
        }
    }

    /**
     * 使用带令牌的 Redis 锁避免并发补偿；释放时校验令牌，防止过期锁的持有者误删新锁。
     */
    private fun withLock(paymentIntentId: String, action: () -> Unit) {
        val key = "lock:pi:$paymentIntentId"
        val token = UUID.randomUUID().toString()
        val acquired = redis.opsForValue().setIfAbsent(key, token, LOCK_TTL) == true
        if (!acquired) {
            throw PaymentOperationBusyException(paymentIntentId)
        }
        try {
            action()
        } finally {
            redis.execute(releaseScript, listOf(key), token)
        }
    }

    private companion object {
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

/** 同一 Stripe 支付资源正在执行补偿，调用方应保留任务并稍后重试。 */
class PaymentOperationBusyException(resourceId: String) :
    RuntimeException("Stripe payment operation is already in progress for $resourceId")
