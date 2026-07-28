package top.foxball.shopmall.service.payment

import com.stripe.StripeClient
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import top.foxball.shopmall.shared.PaymentIntentCoordinator
import top.foxball.shopmall.shared.PaymentOperationBusyException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PaymentIntentCoordinatorTest {
    @Test
    fun `occupied Redis lock fails payment compensation for retry`() {
        val stripeClient = mock(StripeClient::class.java)
        val redis = mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenReturn(false)
        val coordinator = PaymentIntentCoordinator(stripeClient, redis)

        assertFailsWith<PaymentOperationBusyException> {
            coordinator.refund("pi_locked", "ORD-LOCKED:cancelled-order-refund")
        }

        verifyNoInteractions(stripeClient)
    }
}
