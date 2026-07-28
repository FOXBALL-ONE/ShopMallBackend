package top.foxball.shopmall.service.payment

import org.springframework.boot.test.context.runner.ApplicationContextRunner
import top.foxball.shopmall.service.payMent.stripe.StripeConfig
import top.foxball.shopmall.service.payMent.stripe.StripeProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StripePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(StripeConfig::class.java)
        .withPropertyValues(
            "stripe.webhook.endpoint-url=http://localhost:8080/webhook",
            "stripe.checkout.storefront-base-url=http://localhost:3000",
        )

    @Test
    fun `application startup fails without Stripe secret key`() {
        contextRunner
            .withPropertyValues("stripe.webhook-secret=whsec_test_valid")
            .run { context -> assertTrue(context.startupFailure != null) }
    }

    @Test
    fun `application startup fails without Stripe webhook secret`() {
        contextRunner
            .withPropertyValues("stripe.secret-key=sk_test_valid")
            .run { context -> assertTrue(context.startupFailure != null) }
    }

    @Test
    fun `valid Stripe test keys load configuration`() {
        contextRunner
            .withPropertyValues(
                "stripe.secret-key=sk_test_valid",
                "stripe.webhook-secret=whsec_test_valid",
                "stripe.webhook-max-body-bytes=4096",
            )
            .run { context ->
                assertFalse(context.startupFailure != null)
                assertTrue(context.getBean(StripeProperties::class.java).webhookMaxBodyBytes == 4096)
            }
    }

    @Test
    fun `Stripe webhook URL must target the independent callback route`() {
        contextRunner
            .withPropertyValues(
                "stripe.secret-key=sk_test_valid",
                "stripe.webhook-secret=whsec_test_valid",
                "stripe.webhook.endpoint-url=http://localhost:8080/api/orders/webhook",
            )
            .run { context -> assertTrue(context.startupFailure != null) }
    }
}
