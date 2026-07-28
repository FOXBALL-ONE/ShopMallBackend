package top.foxball.shopmall.controller

import com.stripe.StripeClient
import com.stripe.model.Event
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.service.OrderPaymentService
import top.foxball.shopmall.service.payMent.stripe.StripeProperties
import top.foxball.shopmall.shared.ResponseBuilder
import java.net.URI
import kotlin.test.BeforeTest
import kotlin.test.Test

class StripeWebhookControllerTest {
    private lateinit var stripeClient: StripeClient
    private lateinit var paymentService: OrderPaymentService
    private lateinit var mockMvc: MockMvc

    @BeforeTest
    fun setUp() {
        stripeClient = mock(StripeClient::class.java)
        paymentService = mock(OrderPaymentService::class.java)
        val properties = StripeProperties(
            secretKey = "sk_test_valid",
            webhookSecret = "whsec_test_valid",
            webhookMaxBodyBytes = 8,
            webhook = StripeProperties.WebhookProperties(URI("http://localhost:8080/webhook")),
            checkout = StripeProperties.CheckoutProperties(URI("http://localhost:3000")),
        )
        mockMvc = MockMvcBuilders.standaloneSetup(
            StripeWebhookController(stripeClient, properties, paymentService, ResponseBuilder()),
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `independent Stripe webhook is verified and dispatched`() {
        val event = mock(Event::class.java)
        `when`(stripeClient.constructEvent("{}", "test-signature", "whsec_test_valid")).thenReturn(event)

        mockMvc.perform(
            post("/webhook")
                .header("Stripe-Signature", "test-signature")
                .content("{}"),
        )
            .andExpect(status().isOk)

        verify(paymentService).handleWebhookEvent(event)
    }

    @Test
    fun `legacy order route no longer accepts Stripe callbacks`() {
        mockMvc.perform(
            post("/api/orders/webhook")
                .header("Stripe-Signature", "test-signature")
                .content("{}"),
        )
            .andExpect(status().isNotFound)

        verifyNoInteractions(stripeClient, paymentService)
    }

    @Test
    fun `oversized Stripe webhook returns 413 before signature verification`() {
        mockMvc.perform(
            post("/webhook")
                .header("Stripe-Signature", "test-signature")
                .content("123456789"),
        )
            .andExpect(status().isContentTooLarge)
            .andExpect(jsonPath("$.status").value(413))

        verifyNoInteractions(stripeClient, paymentService)
    }
}
