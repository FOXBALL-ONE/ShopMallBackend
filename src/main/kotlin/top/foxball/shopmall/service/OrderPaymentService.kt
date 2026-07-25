package top.foxball.shopmall.service

import com.stripe.model.Event
import top.foxball.shopmall.entity.jdbc.OrderEntity

interface OrderPaymentService {
    fun createPaymentIntent(orderId: Long): String?

    fun getClientSecret(order: OrderEntity): String?

    fun handleWebhookEvent(event: Event)

    /** Registers the remote Stripe operation after commit when called inside a transaction. */
    fun cancelOrRefund(order: OrderEntity, reasonKey: String)
}
