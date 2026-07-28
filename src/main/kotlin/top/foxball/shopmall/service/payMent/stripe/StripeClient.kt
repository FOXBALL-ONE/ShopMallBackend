package top.foxball.shopmall.service.payMent.stripe

import com.stripe.StripeClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

@ConfigurationProperties(prefix = "stripe")
data class StripeProperties(
    val secretKey: String,
    val webhookSecret: String = "",
    val webhookMaxBodyBytes: Int = 262_144,
    val webhook: WebhookProperties,
    val checkout: CheckoutProperties,
) {
    init {
        require(secretKey.isNotBlank() && secretKey.startsWith("sk_")) {
            "stripe.secret-key must be a non-blank Stripe secret key starting with sk_"
        }
        require(webhookSecret.isNotBlank() && webhookSecret.startsWith("whsec_")) {
            "stripe.webhook-secret must be a non-blank Stripe webhook secret starting with whsec_"
        }
        require(webhookMaxBodyBytes > 0) {
            "stripe.webhook-max-body-bytes must be positive"
        }
    }

    data class WebhookProperties(
        /** Stripe Dashboard 中登记的回调地址，仅用于启动时校验路由一致性。 */
        val endpointUrl: URI,
    ) {
        init {
            require(endpointUrl.isAbsolute && endpointUrl.host != null) {
                "Stripe webhook endpoint URL must be an absolute URL with a host"
            }
            require(endpointUrl.rawQuery == null && endpointUrl.rawFragment == null) {
                "Stripe webhook endpoint URL must not contain a query or fragment"
            }
            require(
                endpointUrl.scheme == "https" ||
                    (endpointUrl.scheme == "http" && endpointUrl.host == "localhost"),
            ) {
                "Stripe webhook endpoint URL must use HTTPS, except for localhost development"
            }
            require(endpointUrl.path == WEBHOOK_PATH) {
                "Stripe webhook endpoint URL path must be $WEBHOOK_PATH"
            }
        }
    }

    data class CheckoutProperties(
        /** 主站基地址，只由服务端用于构建 Stripe Checkout 回跳地址。 */
        val storefrontBaseUrl: URI,
    ) {
        init {
            require(storefrontBaseUrl.isAbsolute && storefrontBaseUrl.host != null) {
                "Stripe Checkout storefront base URL must be an absolute URL with a host"
            }
            require(storefrontBaseUrl.rawQuery == null && storefrontBaseUrl.rawFragment == null) {
                "Stripe Checkout storefront base URL must not contain a query or fragment"
            }
            require(
                storefrontBaseUrl.scheme == "https" ||
                    (storefrontBaseUrl.scheme == "http" && storefrontBaseUrl.host == "localhost"),
            ) {
                "Stripe Checkout storefront base URL must use HTTPS, except for localhost development"
            }
        }
    }

    companion object {
        const val WEBHOOK_PATH = "/webhook"
    }
}
@Configuration
@EnableConfigurationProperties(StripeProperties::class)
class StripeConfig {
    @Bean
    fun stripeClient(properties: StripeProperties): StripeClient =
        StripeClient(properties.secretKey)
}
