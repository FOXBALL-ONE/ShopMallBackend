package top.foxball.shopmall.service.payment

import com.stripe.StripeClient
import com.stripe.model.Charge
import com.stripe.model.PaymentIntent
import com.stripe.param.PaymentIntentRetrieveParams
import com.stripe.service.PaymentIntentService
import com.stripe.service.V1Services
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.foxball.shopmall.service.payMent.stripe.StripeProperties
import top.foxball.shopmall.service.payMent.stripe.StripeService
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StripeServiceReceiptTest {
    private val stripeClient = mock(StripeClient::class.java)
    private val v1Services = mock(V1Services::class.java)
    private val paymentIntents = mock(PaymentIntentService::class.java)
    private val service = StripeService(
        stripeClient,
        StripeProperties(
            secretKey = "sk_test_receipts",
            webhookSecret = "whsec_test_receipts",
            webhook = StripeProperties.WebhookProperties(URI("http://localhost:8080/webhook")),
            checkout = StripeProperties.CheckoutProperties(URI("http://localhost:3000")),
        ),
    )

    init {
        `when`(stripeClient.v1()).thenReturn(v1Services)
        `when`(v1Services.paymentIntents()).thenReturn(paymentIntents)
    }

    @Test
    fun `payment receipt lookup expands latest charge and returns Stripe URL`() {
        val charge = Charge().also {
            it.receiptUrl = "https://pay.stripe.com/receipts/payment-123"
        }
        val paymentIntent = PaymentIntent().also {
            it.latestChargeObject = charge
        }
        `when`(
            paymentIntents.retrieve(eq("pi_payment_123"), any(PaymentIntentRetrieveParams::class.java)),
        ).thenReturn(paymentIntent)

        val receiptUrl = service.retrievePaymentReceiptUrl("pi_payment_123")

        assertEquals("https://pay.stripe.com/receipts/payment-123", receiptUrl)
        val params = ArgumentCaptor.forClass(PaymentIntentRetrieveParams::class.java)
        verify(paymentIntents).retrieve(eq("pi_payment_123"), params.capture())
        assertEquals(listOf("latest_charge"), params.value.expand)
    }

    @Test
    fun `payment receipt lookup fails when Stripe omits receipt URL`() {
        `when`(
            paymentIntents.retrieve(eq("pi_without_receipt"), any(PaymentIntentRetrieveParams::class.java)),
        ).thenReturn(PaymentIntent())

        assertFailsWith<IllegalStateException> {
            service.retrievePaymentReceiptUrl("pi_without_receipt")
        }
    }
}
