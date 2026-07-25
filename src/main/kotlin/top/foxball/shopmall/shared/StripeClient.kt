package top.foxball.shopmall.shared

import com.stripe.StripeClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "stripe")
data class StripeProperties(
    val secretKey: String,
    val webhookSecret: String = "",
)

@Configuration
@EnableConfigurationProperties(StripeProperties::class)
class StripeConfig {
    
    @Bean
    fun stripeClient(properties: StripeProperties): StripeClient =
        StripeClient(properties.secretKey)
}
