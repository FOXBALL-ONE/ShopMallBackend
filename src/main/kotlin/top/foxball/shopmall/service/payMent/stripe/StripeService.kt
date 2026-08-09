package top.foxball.shopmall.service.payMent.stripe

import com.stripe.StripeClient
import com.stripe.exception.ApiConnectionException
import com.stripe.exception.ApiException
import com.stripe.exception.ApiKeyMissingException
import com.stripe.exception.AuthenticationException
import com.stripe.exception.CardException
import com.stripe.exception.IdempotencyException
import com.stripe.exception.InvalidRequestException
import com.stripe.exception.PermissionException
import com.stripe.exception.RateLimitException
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import com.stripe.model.Refund
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentCancelParams
import com.stripe.param.RefundCreateParams
import com.stripe.param.checkout.SessionCreateParams
import org.springframework.stereotype.Service
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentCancelRequest
import top.foxball.shopmall.service.payMent.PaymentCapabilities
import top.foxball.shopmall.service.payMent.PaymentClientAction
import top.foxball.shopmall.service.payMent.PaymentCreateRequest
import top.foxball.shopmall.service.payMent.PaymentInterface
import top.foxball.shopmall.service.payMent.PaymentProviderError
import top.foxball.shopmall.service.payMent.PaymentProviderException
import top.foxball.shopmall.service.payMent.PaymentProviderId
import top.foxball.shopmall.service.payMent.PaymentQueryRequest
import top.foxball.shopmall.service.payMent.PaymentRefund
import top.foxball.shopmall.service.payMent.PaymentRefundQueryRequest
import top.foxball.shopmall.service.payMent.PaymentRefundRequest
import top.foxball.shopmall.service.payMent.PaymentRefundStatus
import top.foxball.shopmall.service.payMent.PaymentStatus
import top.foxball.shopmall.service.payMent.PaymentTransaction
import top.foxball.shopmall.service.payMent.PaymentWebhookEvent
import top.foxball.shopmall.service.payMent.PaymentWebhookRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Instant

/** Stripe Checkout 与项目支付契约之间的无状态适配器。 */
@Service
class StripeService(
    private val stripeClient: StripeClient,
    private val properties: StripeProperties,
) : PaymentInterface {
    override val provider = PaymentProviderId("stripe")

    override val capabilities = PaymentCapabilities(
        cancellation = true,
        refund = true,
        partialRefund = true,
        webhook = true,
    )

    /** 创建 Stripe 托管收银台会话，金额和回跳地址均来自服务端调用方。 */
    override fun createPayment(request: PaymentCreateRequest): PaymentTransaction {
        val successUrl = requireNotNull(request.returnUrl) { "Stripe Checkout requires a success URL" }
        val cancelUrl = requireNotNull(request.cancelUrl) { "Stripe Checkout requires a cancel URL" }
        val minorAmount = toMinorUnit(request.amount)

        val params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(successUrl.toStripeUrl())
            .setCancelUrl(cancelUrl.toStripeUrl())
            .setClientReferenceId(request.merchantPaymentId)
            .setPaymentIntentData(
                SessionCreateParams.PaymentIntentData.builder()
                    .putMetadata("merchantPaymentId", request.merchantPaymentId)
                    .build(),
            )
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(request.amount.currency.lowercase())
                            .setUnitAmount(minorAmount)
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(request.description ?: "Order ${request.merchantPaymentId}")
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .apply {
                request.expiresAt?.let { setExpiresAt(it.epochSecond) }
                request.metadata.forEach(::putMetadata)
            }
            .build()
        val session = stripeCall {
            stripeClient.v1().checkout().sessions().create(
                params,
                RequestOptions.builder().setIdempotencyKey(request.idempotencyKey).build(),
            )
        }
        return session.toCheckoutTransaction(request.amount)
    }

    override fun queryPayment(request: PaymentQueryRequest): PaymentTransaction =
        stripeCall { stripeClient.v1().paymentIntents().retrieve(request.providerPaymentId) }
            .toPaymentTransaction()

    /** 此方法仅用于非 Checkout 的兼容支付；Checkout 会话应先由订单层过期。 */
    override fun cancelPayment(request: PaymentCancelRequest): PaymentTransaction =
        stripeCall {
            stripeClient.v1().paymentIntents().cancel(
                request.providerPaymentId,
                PaymentIntentCancelParams.builder().build(),
                RequestOptions.builder().setIdempotencyKey(request.idempotencyKey).build(),
            )
        }.toPaymentTransaction()

    override fun refundPayment(request: PaymentRefundRequest): PaymentRefund {
        val params = RefundCreateParams.builder()
            .setPaymentIntent(request.providerPaymentId)
            .putMetadata("merchantRefundId", request.merchantRefundId)
            .apply {
                request.amount?.let { setAmount(toMinorUnit(it)) }
                request.reason?.let { putMetadata("reason", it) }
            }
            .build()
        return stripeCall {
            stripeClient.v1().refunds().create(
                params,
                RequestOptions.builder().setIdempotencyKey(request.idempotencyKey).build(),
            )
        }.toPaymentRefund()
    }

    override fun queryRefund(request: PaymentRefundQueryRequest): PaymentRefund {
        val providerRefundId = request.providerRefundId ?: throw unsupported(
            "Stripe refunds can only be queried by provider refund id",
        )
        return stripeCall { stripeClient.v1().refunds().retrieve(providerRefundId) }.toPaymentRefund()
    }

    override fun parseWebhook(request: PaymentWebhookRequest): PaymentWebhookEvent {
        val signature = request.header("Stripe-Signature").singleOrNull()
            ?: throw PaymentProviderException(
                provider = provider,
                error = PaymentProviderError.SIGNATURE_VERIFICATION,
                retryable = false,
                message = "Stripe webhook signature is missing or duplicated",
            )
        val event = stripeCall {
            stripeClient.constructEvent(request.payload.toString(Charsets.UTF_8), signature, properties.webhookSecret)
        }
        return event.toWebhookEvent()
    }

    /** 查询 Checkout 会话，以便在重复请求时安全复用 Stripe 返回的跳转地址。 */
    fun retrieveCheckoutSession(sessionId: String): StripeCheckoutSession =
        stripeCall { stripeClient.v1().checkout().sessions().retrieve(sessionId) }.toCheckoutSession()

    /** 仅使仍处于 open 状态的 Checkout 会话失效，已完成或已过期的会话保持原状态。 */
    fun expireCheckoutSession(sessionId: String): StripeCheckoutSession {
        val session = stripeCall { stripeClient.v1().checkout().sessions().retrieve(sessionId) }
        val result = if (session.status == "open") {
            stripeCall { stripeClient.v1().checkout().sessions().expire(sessionId) }
        } else {
            session
        }
        return result.toCheckoutSession()
    }

    private fun Session.toCheckoutTransaction(amount: PaymentAmount): PaymentTransaction {
        val sessionId = requireNotNull(id) { "Stripe Checkout Session id is missing" }
        val sessionUrl = requireNotNull(url) { "Stripe Checkout Session URL is missing" }
        return PaymentTransaction(
            providerPaymentId = paymentIntent,
            amount = amount,
            status = checkoutStatus(status, paymentStatus),
            clientAction = PaymentClientAction.Redirect(URI(sessionUrl)),
            checkoutReference = sessionId,
            rawStatus = status,
            expiresAt = expiresAt?.let(Instant::ofEpochSecond),
        )
    }

    private fun Session.toCheckoutSession(): StripeCheckoutSession = StripeCheckoutSession(
        id = requireNotNull(id) { "Stripe Checkout Session id is missing" },
        paymentIntentId = paymentIntent,
        url = url,
        status = status,
        expiresAt = expiresAt?.let(Instant::ofEpochSecond),
        paymentStatus = paymentStatus,
        amount = amountTotal?.let { paymentAmount(it, currency) },
        collectionStatus = checkoutStatus(status, paymentStatus),
    )

    private fun PaymentIntent.toPaymentTransaction(): PaymentTransaction = PaymentTransaction(
        providerPaymentId = requireNotNull(id) { "Stripe PaymentIntent id is missing" },
        amount = paymentAmount(requireNotNull(amount) { "Stripe PaymentIntent amount is missing" }, currency),
        status = paymentStatus(status),
        rawStatus = status,
        failureCode = lastPaymentError?.code,
        failureMessage = lastPaymentError?.message,
    )

    private fun Refund.toPaymentRefund(): PaymentRefund = PaymentRefund(
        providerRefundId = requireNotNull(id) { "Stripe Refund id is missing" },
        providerPaymentId = requireNotNull(paymentIntent) { "Stripe Refund payment intent is missing" },
        amount = paymentAmount(requireNotNull(amount) { "Stripe Refund amount is missing" }, currency),
        status = refundStatus(status),
        rawStatus = status,
    )

    private fun Event.toWebhookEvent(): PaymentWebhookEvent {
        val eventId = requireNotNull(id) { "Stripe webhook event id is missing" }
        val eventType = requireNotNull(type) { "Stripe webhook event type is missing" }
        val occurredAt = created?.let(Instant::ofEpochSecond)
        val eventObject = dataObjectDeserializer.getObject().orElse(null)
        return when (eventObject) {
            is Session -> {
                val paymentIntentId = eventObject.paymentIntent ?: return PaymentWebhookEvent.Ignored(
                    provider = provider,
                    providerEventId = eventId,
                    rawType = eventType,
                    occurredAt = occurredAt,
                )
                PaymentWebhookEvent.PaymentStatusChanged(
                    provider = provider,
                    providerEventId = eventId,
                    rawType = eventType,
                    occurredAt = occurredAt,
                    providerPaymentId = paymentIntentId,
                    status = checkoutStatus(eventObject.status, eventObject.paymentStatus),
                    amount = eventObject.amountTotal?.let { paymentAmount(it, eventObject.currency) },
                )
            }

            is Refund -> {
                val paymentIntentId = eventObject.paymentIntent ?: return PaymentWebhookEvent.Ignored(
                    provider = provider,
                    providerEventId = eventId,
                    rawType = eventType,
                    occurredAt = occurredAt,
                )
                PaymentWebhookEvent.RefundStatusChanged(
                    provider = provider,
                    providerEventId = eventId,
                    rawType = eventType,
                    occurredAt = occurredAt,
                    providerPaymentId = paymentIntentId,
                    providerRefundId = requireNotNull(eventObject.id) { "Stripe Refund id is missing" },
                    status = refundStatus(eventObject.status),
                    amount = eventObject.amount?.let { paymentAmount(it, eventObject.currency) },
                )
            }

            else -> PaymentWebhookEvent.Ignored(provider, eventId, eventType, occurredAt)
        }
    }

    private fun toMinorUnit(amount: PaymentAmount): Long {
        requireSupportedCurrency(amount.currency)
        return amount.value.movePointRight(MINOR_UNIT_SCALE)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    }

    private fun paymentAmount(minorAmount: Long, currency: String?): PaymentAmount {
        val normalizedCurrency = requireNotNull(currency) { "Stripe amount currency is missing" }.uppercase()
        requireSupportedCurrency(normalizedCurrency)
        return PaymentAmount(
            value = BigDecimal.valueOf(minorAmount).movePointLeft(MINOR_UNIT_SCALE),
            currency = normalizedCurrency,
        )
    }

    private fun requireSupportedCurrency(currency: String) {
        require(currency in SUPPORTED_CURRENCIES) {
            "Stripe Checkout currently supports only ${SUPPORTED_CURRENCIES.joinToString()}"
        }
    }

    private fun paymentStatus(status: String?): PaymentStatus = when (status) {
        "requires_payment_method", "requires_confirmation", "requires_action" -> PaymentStatus.REQUIRES_ACTION
        "processing", "requires_capture" -> PaymentStatus.PROCESSING
        "succeeded" -> PaymentStatus.SUCCEEDED
        "canceled" -> PaymentStatus.CANCELLED
        else -> PaymentStatus.UNKNOWN
    }

    private fun checkoutStatus(sessionStatus: String?, sessionPaymentStatus: String?): PaymentStatus = when {
        sessionPaymentStatus == "paid" -> PaymentStatus.SUCCEEDED
        sessionStatus == "open" -> PaymentStatus.PENDING
        sessionStatus == "complete" -> PaymentStatus.PROCESSING
        sessionStatus == "expired" -> PaymentStatus.CANCELLED
        else -> PaymentStatus.UNKNOWN
    }

    private fun refundStatus(status: String?): PaymentRefundStatus = when (status) {
        "pending", "requires_action" -> PaymentRefundStatus.PENDING
        "succeeded" -> PaymentRefundStatus.SUCCEEDED
        "failed" -> PaymentRefundStatus.FAILED
        "canceled" -> PaymentRefundStatus.CANCELLED
        else -> PaymentRefundStatus.UNKNOWN
    }

    private fun unsupported(message: String): PaymentProviderException = PaymentProviderException(
        provider = provider,
        error = PaymentProviderError.UNSUPPORTED_OPERATION,
        retryable = false,
        message = message,
    )

    private fun <T> stripeCall(action: () -> T): T = try {
        action()
    } catch (ex: StripeException) {
        throw ex.toPaymentProviderException(provider)
    } catch (ex: ApiKeyMissingException) {
        throw PaymentProviderException(
            provider = provider,
            error = PaymentProviderError.AUTHENTICATION,
            retryable = false,
            message = ex.message ?: "Stripe API key is missing",
            cause = ex,
        )
    }

    private companion object {
        const val MINOR_UNIT_SCALE = 2
        val SUPPORTED_CURRENCIES = setOf("USD", "EUR")
    }

    /** URI 会转义 Checkout 占位符；向 Stripe 发送参数前恢复它要求的字面量。 */
    private fun URI.toStripeUrl(): String = toASCIIString()
        .replace("%7BCHECKOUT_SESSION_ID%7D", "{CHECKOUT_SESSION_ID}")
}

/** 先按 Stripe HTTP 状态分类，再用异常类型补足没有状态码的客户端错误。 */
internal fun StripeException.toPaymentProviderException(provider: PaymentProviderId): PaymentProviderException {
    val httpStatus = statusCode
    val (error, retryable) = when {
        httpStatus == 404 -> PaymentProviderError.PAYMENT_NOT_FOUND to false
        httpStatus == 409 -> PaymentProviderError.CONFLICT to false
        httpStatus == 429 -> PaymentProviderError.RATE_LIMITED to true
        httpStatus != null && httpStatus >= 500 -> PaymentProviderError.TEMPORARILY_UNAVAILABLE to true
        httpStatus == 401 || httpStatus == 403 -> PaymentProviderError.AUTHENTICATION to false
        this is SignatureVerificationException -> PaymentProviderError.SIGNATURE_VERIFICATION to false
        httpStatus == 400 -> PaymentProviderError.INVALID_REQUEST to false
        this is AuthenticationException || this is PermissionException ->
            PaymentProviderError.AUTHENTICATION to false
        this is RateLimitException -> PaymentProviderError.RATE_LIMITED to true
        this is InvalidRequestException || this is IdempotencyException || this is CardException ->
            PaymentProviderError.INVALID_REQUEST to false
        this is ApiConnectionException -> PaymentProviderError.TEMPORARILY_UNAVAILABLE to true
        this is ApiException -> PaymentProviderError.UNKNOWN to false
        else -> PaymentProviderError.UNKNOWN to false
    }
    return PaymentProviderException(
        provider = provider,
        error = error,
        retryable = retryable,
        message = userMessage ?: message ?: "Stripe request failed",
        providerErrorCode = code,
        cause = this,
    )
}

/** 订单层复用或失效 Checkout 会话时需要的最小远端状态。 */
data class StripeCheckoutSession(
    val id: String,
    val paymentIntentId: String?,
    val url: String?,
    val status: String?,
    val expiresAt: Instant?,
    val paymentStatus: String? = null,
    val amount: PaymentAmount? = null,
    val collectionStatus: PaymentStatus = PaymentStatus.UNKNOWN,
)
