package top.foxball.shopmall.service.payment

import java.math.BigDecimal
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentCapabilities
import top.foxball.shopmall.service.payMent.PaymentCreateRequest
import top.foxball.shopmall.service.payMent.PaymentProviderId
import top.foxball.shopmall.service.payMent.PaymentRefundQueryRequest
import top.foxball.shopmall.service.payMent.PaymentWebhookRequest

class PaymentInterfaceTest {
    @Test
    fun `accepts a provider-neutral payment request`() {
        val request = PaymentCreateRequest(
            merchantPaymentId = "ORDER-100",
            amount = PaymentAmount(BigDecimal("19.99"), "USD"),
            idempotencyKey = "ORDER-100:create-payment",
            returnUrl = URI("https://shop.example/orders/ORDER-100"),
            metadata = mapOf("orderNo" to "ORDER-100"),
        )

        assertEquals("ORDER-100", request.merchantPaymentId)
        assertEquals("19.99", request.amount.value.toPlainString())
    }

    @Test
    fun `rejects invalid provider ids and monetary values`() {
        assertFailsWith<IllegalArgumentException> { PaymentProviderId("Stripe") }
        assertFailsWith<IllegalArgumentException> { PaymentProviderId("stripe-") }
        assertFailsWith<IllegalArgumentException> {
            PaymentAmount(BigDecimal.ZERO, "USD")
        }
        assertFailsWith<IllegalArgumentException> {
            PaymentAmount(BigDecimal.ONE, "usd")
        }
        assertFailsWith<IllegalArgumentException> {
            PaymentAmount(BigDecimal.ONE, "ZZZ")
        }
    }

    @Test
    fun `partial refund capability requires refund support`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentCapabilities(
                cancellation = true,
                refund = false,
                partialRefund = true,
                webhook = true,
            )
        }
    }

    @Test
    fun `webhook headers are case insensitive and input is copied`() {
        val payload = "event".encodeToByteArray()
        val request = PaymentWebhookRequest(
            payload = payload,
            headers = mapOf("Stripe-Signature" to listOf("signature")),
        )

        payload[0] = 'X'.code.toByte()

        assertEquals("event", request.payload.decodeToString())
        assertEquals(listOf("signature"), request.header("stripe-signature"))
    }

    @Test
    fun `refund query requires a provider or merchant refund id`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentRefundQueryRequest(providerPaymentId = "pi_100")
        }
    }
}
