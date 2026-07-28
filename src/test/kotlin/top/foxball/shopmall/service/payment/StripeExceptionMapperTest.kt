package top.foxball.shopmall.service.payment

import com.stripe.exception.ApiConnectionException
import com.stripe.exception.ApiException
import com.stripe.exception.IdempotencyException
import com.stripe.exception.InvalidRequestException
import com.stripe.exception.RateLimitException
import top.foxball.shopmall.service.payMent.PaymentProviderError
import top.foxball.shopmall.service.payMent.PaymentProviderId
import top.foxball.shopmall.service.payMent.stripe.toPaymentProviderException
import kotlin.test.Test
import kotlin.test.assertEquals

class StripeExceptionMapperTest {
    private val provider = PaymentProviderId("stripe")

    @Test
    fun `Stripe HTTP and network failures map to stable provider errors`() {
        val cases = listOf(
            MappingCase(
                ApiException("bad request", "req_400", "bad_request", 400, null),
                PaymentProviderError.INVALID_REQUEST,
                false,
            ),
            MappingCase(
                ApiException("unauthorized", "req_401", "auth", 401, null),
                PaymentProviderError.AUTHENTICATION,
                false,
            ),
            MappingCase(
                ApiException("forbidden", "req_403", "permission", 403, null),
                PaymentProviderError.AUTHENTICATION,
                false,
            ),
            MappingCase(
                InvalidRequestException("missing", "payment_intent", "req_404", "resource_missing", 404, null),
                PaymentProviderError.PAYMENT_NOT_FOUND,
                false,
            ),
            MappingCase(
                IdempotencyException("conflict", "req_409", "idempotency_error", 409),
                PaymentProviderError.CONFLICT,
                false,
            ),
            MappingCase(
                RateLimitException("limited", null, "req_429", "rate_limit", 429, null),
                PaymentProviderError.RATE_LIMITED,
                true,
            ),
            MappingCase(
                ApiException("server error", "req_500", "api_error", 500, null),
                PaymentProviderError.TEMPORARILY_UNAVAILABLE,
                true,
            ),
            MappingCase(
                ApiConnectionException("network unavailable"),
                PaymentProviderError.TEMPORARILY_UNAVAILABLE,
                true,
            ),
        )

        cases.forEach { case ->
            val mapped = case.exception.toPaymentProviderException(provider)
            assertEquals(case.error, mapped.error)
            assertEquals(case.retryable, mapped.retryable)
        }
    }

    private data class MappingCase(
        val exception: com.stripe.exception.StripeException,
        val error: PaymentProviderError,
        val retryable: Boolean,
    )
}
