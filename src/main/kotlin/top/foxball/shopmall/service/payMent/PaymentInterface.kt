package top.foxball.shopmall.service.payMent

import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.Currency

/**
 * 与支付提供商无关的统一支付契约。
 *
 * 实现类负责在这些领域类型与提供商 SDK 类型之间转换，不得修改订单或开启数据库事务。
 * 持久化、重试及订单状态变更由调用方负责。
 * 当 [capabilities] 声明某项能力不可用时，对应方法必须抛出 [PaymentProviderException]，
 * 且错误类型必须为 [PaymentProviderError.UNSUPPORTED_OPERATION]。
 */
interface PaymentInterface {
    /** 用于配置、日志和持久化记录的稳定小写提供商标识。 */
    val provider: PaymentProviderId

    val capabilities: PaymentCapabilities

    /**
     * 为 [PaymentCreateRequest.merchantPaymentId] 创建一笔提供商侧支付。
     * 使用相同幂等键重复提交相同请求时，必须返回首次创建的支付结果。
     */
    @Throws(PaymentProviderException::class)
    fun createPayment(request: PaymentCreateRequest): PaymentTransaction

    /** 查询支付在提供商侧的最新状态，不得产生状态变更。 */
    @Throws(PaymentProviderException::class)
    fun queryPayment(request: PaymentQueryRequest): PaymentTransaction

    /**
     * 取消一笔尚未完成的支付。
     * 当提供商已将支付标记为取消时，重复取消必须按成功处理。
     */
    @Throws(PaymentProviderException::class)
    fun cancelPayment(request: PaymentCancelRequest): PaymentTransaction

    /** 当退款金额为 null 时创建全额退款，否则创建部分退款。 */
    @Throws(PaymentProviderException::class)
    fun refundPayment(request: PaymentRefundRequest): PaymentRefund

    /** 查询退款在提供商侧的最新状态，不得产生状态变更。 */
    @Throws(PaymentProviderException::class)
    fun queryRefund(request: PaymentRefundQueryRequest): PaymentRefund

    /**
     * 必须先验证提供商签名，再解析回调内容。
     * 验签失败时必须报告为 [PaymentProviderError.SIGNATURE_VERIFICATION]。
     */
    @Throws(PaymentProviderException::class)
    fun parseWebhook(request: PaymentWebhookRequest): PaymentWebhookEvent
}

@JvmInline
value class PaymentProviderId(val value: String) {
    init {
        require(value.length <= MAX_LENGTH && PROVIDER_ID.matches(value)) {
            "Payment provider id must be lowercase alphanumeric kebab-case and at most 32 characters"
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 32
        val PROVIDER_ID = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
    }
}

data class PaymentCapabilities(
    val cancellation: Boolean,
    val refund: Boolean,
    val partialRefund: Boolean,
    val webhook: Boolean,
) {
    init {
        require(!partialRefund || refund) { "Partial refunds require refund support" }
    }
}

data class PaymentAmount(
    val value: BigDecimal,
    val currency: String,
) {
    init {
        require(value > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        require(
            CURRENCY.matches(currency) && runCatching { Currency.getInstance(currency) }.isSuccess,
        ) { "Currency must be an uppercase ISO 4217 code" }
    }

    private companion object {
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}

data class PaymentCreateRequest(
    /** 商户侧支付标识，通常使用订单号。 */
    val merchantPaymentId: String,
    val amount: PaymentAmount,
    val idempotencyKey: String,
    val description: String? = null,
    /** 支付成功后的主站回跳地址。 */
    val returnUrl: URI? = null,
    /** 客户取消或离开支付页后的主站回跳地址。 */
    val cancelUrl: URI? = null,
    /** 托管支付页的服务端失效时间。 */
    val expiresAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(merchantPaymentId.isNotBlank()) { "Merchant payment id must not be blank" }
        require(idempotencyKey.isNotBlank()) { "Payment idempotency key must not be blank" }
        require(description == null || description.isNotBlank()) { "Description must not be blank" }
        require(returnUrl == null || returnUrl.isAbsolute) { "Return URL must be absolute" }
        require(cancelUrl == null || cancelUrl.isAbsolute) { "Cancel URL must be absolute" }
        require(metadata.keys.none(String::isBlank)) { "Metadata keys must not be blank" }
    }
}

data class PaymentQueryRequest(
    val providerPaymentId: String,
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
    }
}

data class PaymentCancelRequest(
    val providerPaymentId: String,
    val idempotencyKey: String,
    val reason: String? = null,
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
        require(idempotencyKey.isNotBlank()) { "Cancellation idempotency key must not be blank" }
        require(reason == null || reason.isNotBlank()) { "Cancellation reason must not be blank" }
    }
}

data class PaymentRefundRequest(
    val providerPaymentId: String,
    /** 商户侧退款标识，每次退款操作必须保持唯一。 */
    val merchantRefundId: String,
    val idempotencyKey: String,
    /** 为 null 时退还当前剩余的全部可退款金额。 */
    val amount: PaymentAmount? = null,
    val reason: String? = null,
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
        require(merchantRefundId.isNotBlank()) { "Merchant refund id must not be blank" }
        require(idempotencyKey.isNotBlank()) { "Refund idempotency key must not be blank" }
        require(reason == null || reason.isNotBlank()) { "Refund reason must not be blank" }
    }
}

data class PaymentTransaction(
    /** 可查询、取消或退款的提供商侧交易标识；Stripe Checkout 对应 pi_ 前缀。 */
    val providerPaymentId: String?,
    val amount: PaymentAmount,
    val status: PaymentStatus,
    val clientAction: PaymentClientAction = PaymentClientAction.None,
    /** 托管收银台或跳转会话标识；Stripe Checkout 对应 cs_ 前缀。 */
    val checkoutReference: String? = null,
    /** 提供商返回的原始状态，用于问题诊断及后续补充状态映射。 */
    val rawStatus: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val expiresAt: Instant? = null,
) {
    init {
        require(providerPaymentId == null || providerPaymentId.isNotBlank()) {
            "Provider payment id must not be blank"
        }
        require(providerPaymentId != null || checkoutReference != null) {
            "A provider payment id or checkout reference is required"
        }
        require(checkoutReference == null || checkoutReference.isNotBlank()) {
            "Checkout reference must not be blank"
        }
        require(rawStatus == null || rawStatus.isNotBlank()) { "Raw payment status must not be blank" }
        require(failureCode == null || failureCode.isNotBlank()) { "Failure code must not be blank" }
        require(failureMessage == null || failureMessage.isNotBlank()) { "Failure message must not be blank" }
    }
}

enum class PaymentStatus {
    REQUIRES_ACTION,
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    UNKNOWN,
}

sealed interface PaymentClientAction {
    data object None : PaymentClientAction

    data class ClientSecret(val value: String) : PaymentClientAction {
        init {
            require(value.isNotBlank()) { "Client secret must not be blank" }
        }
    }

    data class Redirect(val url: URI) : PaymentClientAction {
        init {
            require(url.isAbsolute) { "Redirect URL must be absolute" }
        }
    }

    data class QrCode(val content: String) : PaymentClientAction {
        init {
            require(content.isNotBlank()) { "QR code content must not be blank" }
        }
    }

    /** 传递给提供商官方浏览器端或移动端 SDK 的不透明参数。 */
    data class SdkPayload(val fields: Map<String, String>) : PaymentClientAction {
        init {
            require(fields.isNotEmpty()) { "SDK payload must not be empty" }
            require(fields.keys.none(String::isBlank)) { "SDK payload keys must not be blank" }
        }
    }
}

data class PaymentRefund(
    val providerRefundId: String,
    val providerPaymentId: String,
    val amount: PaymentAmount,
    val status: PaymentRefundStatus,
    val rawStatus: String? = null,
) {
    init {
        require(providerRefundId.isNotBlank()) { "Provider refund id must not be blank" }
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
        require(rawStatus == null || rawStatus.isNotBlank()) { "Raw refund status must not be blank" }
    }
}

data class PaymentRefundQueryRequest(
    val providerPaymentId: String,
    val providerRefundId: String? = null,
    val merchantRefundId: String? = null,
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
        require(providerRefundId == null || providerRefundId.isNotBlank()) {
            "Provider refund id must not be blank"
        }
        require(merchantRefundId == null || merchantRefundId.isNotBlank()) {
            "Merchant refund id must not be blank"
        }
        require(providerRefundId != null || merchantRefundId != null) {
            "A provider or merchant refund id is required"
        }
    }
}

enum class PaymentRefundStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

class PaymentWebhookRequest(
    payload: ByteArray,
    headers: Map<String, List<String>>,
) {
    private val body: ByteArray = payload.copyOf()
    private val headerValues: Map<String, List<String>> =
        headers.mapValues { (_, values) -> values.toList() }

    val payload: ByteArray
        get() = body.copyOf()

    val headers: Map<String, List<String>>
        get() = headerValues.mapValues { (_, values) -> values.toList() }

    /** HTTP 请求头名称不区分大小写。 */
    fun header(name: String): List<String> = headerValues
        .filterKeys { it.equals(name, ignoreCase = true) }
        .values
        .flatten()
}

sealed interface PaymentWebhookEvent {
    val provider: PaymentProviderId
    /** 提供商返回的稳定事件标识，调用方使用该标识保证回调处理幂等。 */
    val providerEventId: String
    val rawType: String
    val occurredAt: Instant?

    data class PaymentStatusChanged(
        override val provider: PaymentProviderId,
        override val providerEventId: String,
        override val rawType: String,
        override val occurredAt: Instant?,
        val providerPaymentId: String,
        val status: PaymentStatus,
        val amount: PaymentAmount? = null,
    ) : PaymentWebhookEvent {
        init {
            validateEvent(providerEventId, rawType)
            require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
        }
    }

    data class RefundStatusChanged(
        override val provider: PaymentProviderId,
        override val providerEventId: String,
        override val rawType: String,
        override val occurredAt: Instant?,
        val providerPaymentId: String,
        val providerRefundId: String,
        val status: PaymentRefundStatus,
        val amount: PaymentAmount? = null,
    ) : PaymentWebhookEvent {
        init {
            validateEvent(providerEventId, rawType)
            require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
            require(providerRefundId.isNotBlank()) { "Provider refund id must not be blank" }
        }
    }

    /** 已通过验签但不属于当前应用支付契约处理范围的提供商事件。 */
    data class Ignored(
        override val provider: PaymentProviderId,
        override val providerEventId: String,
        override val rawType: String,
        override val occurredAt: Instant?,
    ) : PaymentWebhookEvent {
        init {
            validateEvent(providerEventId, rawType)
        }
    }
}

enum class PaymentProviderError {
    INVALID_REQUEST,
    AUTHENTICATION,
    SIGNATURE_VERIFICATION,
    PAYMENT_NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    TEMPORARILY_UNAVAILABLE,
    UNSUPPORTED_OPERATION,
    UNKNOWN,
}

/** 标准化的提供商或网络传输异常；业务规则异常应由业务服务层处理。 */
class PaymentProviderException(
    val provider: PaymentProviderId,
    val error: PaymentProviderError,
    val retryable: Boolean,
    message: String,
    val providerErrorCode: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    init {
        require(message.isNotBlank()) { "Provider error message must not be blank" }
        require(providerErrorCode == null || providerErrorCode.isNotBlank()) {
            "Provider error code must not be blank"
        }
    }
}

private fun validateEvent(providerEventId: String, rawType: String) {
    require(providerEventId.isNotBlank()) { "Provider event id must not be blank" }
    require(rawType.isNotBlank()) { "Raw webhook event type must not be blank" }
}
