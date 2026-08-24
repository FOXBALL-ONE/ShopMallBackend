package top.foxball.shopmall.service

import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mail.javamail.JavaMailSender
import tools.jackson.databind.json.JsonMapper
import top.foxball.shopmall.config.OrderMailProperties
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.OrderMailServiceImpl
import top.foxball.shopmall.service.payMent.PaymentAmount
import top.foxball.shopmall.service.payMent.PaymentRefund
import top.foxball.shopmall.service.payMent.PaymentRefundStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderMailServiceImplTest {
    private val mailSender = mock(JavaMailSender::class.java)
    private val orderRepository = mock(OrderRepository::class.java)
    private val orderItemRepository = mock(OrderItemRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val service = OrderMailServiceImpl(
        mailSender = mailSender,
        orderRepository = orderRepository,
        orderItemRepository = orderItemRepository,
        userRepository = userRepository,
        objectMapper = JsonMapper(),
        properties = OrderMailProperties(
            from = "orders@pelissa.example",
            subjectPrefix = "PELISSA",
        ),
        mailUsername = "fallback@pelissa.example",
        storefrontBaseUrl = "https://shop.pelissa.example/",
    )

    @Test
    fun `blank payment confirmation subject prefix falls back to the default brand`() {
        assertEquals("PELISSA", OrderMailProperties(subjectPrefix = " ").subjectPrefix)
    }

    @Test
    fun `payment confirmation renders the order snapshot as escaped html and plaintext`() {
        val order = OrderEntity(
            id = 7,
            orderNo = "PS-20260809-001",
            customerId = 12,
            status = OrderStatus.PAID,
            itemsSubtotal = BigDecimal("88.00"),
            shippingFee = BigDecimal("8.00"),
            taxAmount = BigDecimal("4.80"),
            discountAmount = BigDecimal("10.00"),
            totalAmount = BigDecimal("90.80"),
            currency = "USD",
            shippingAddress = OrderShippingAddress(
                name = "Ava & Zoe",
                phone = "+14155550123",
                country = "US",
                stateOrProvince = "California",
                city = "San Francisco",
                district = "Mission & SoMa",
                postalCode = "94110",
                address1 = "45 & Fig Lane",
                company = "Atelier <North>",
                deliveryInstructions = "Use the <blue> door & ring",
            ),
            clientMessage = "Leave at <front> desk & ring",
            paidAt = Instant.parse("2026-08-09T08:30:45Z"),
        )
        val customer = User(
            id = 12,
            email = "ava@example.com",
            username = "ava",
            password = "encoded-password",
            firstName = "Ava",
            lastName = "& Zoe",
        )
        val item = OrderItem(
            productId = 31,
            variantId = 310,
            sku = "SILK-LACE-M",
            productSnapshot = """{"name":"Silk & Lace","color":"Rose & Ivory","size":"M"}""",
            quantity = 2,
            lineTotal = BigDecimal("88.00"),
        )
        val message = MimeMessage(Session.getInstance(Properties()))
        `when`(orderRepository.findById(7)).thenReturn(Optional.of(order))
        `when`(userRepository.findById(12)).thenReturn(Optional.of(customer))
        `when`(orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(7)).thenReturn(listOf(item))
        `when`(mailSender.createMimeMessage()).thenReturn(message)

        val stripeReceiptUrl = "https://pay.stripe.com/receipts/payment?payment_intent=pi_123&charge=ch_123"
        service.sendPaymentConfirmation(7, stripeReceiptUrl)
        message.saveChanges()

        assertEquals("PELISSA | Payment received · PS-20260809-001", message.subject)
        assertEquals("ava@example.com", message.allRecipients.single().toString())
        assertEquals("orders@pelissa.example", message.from.single().toString())
        verify(mailSender).send(message)

        val parts = textParts(message.content)
        val plaintext = parts.first { it.startsWith("PELISSA payment received") }
        val html = parts.first { it.contains("<html") }
        assertTrue(plaintext.contains("Silk & Lace"))
        assertTrue(plaintext.contains("USD 90.80"))
        assertTrue(plaintext.contains("https://shop.pelissa.example/account/orders"))
        assertTrue(plaintext.contains("View Stripe receipt: $stripeReceiptUrl"))
        assertTrue(html.contains("Silk &amp; Lace"))
        assertTrue(html.contains("Rose &amp; Ivory"))
        assertTrue(html.contains("Atelier &lt;North&gt;"))
        assertTrue(html.contains("45 &amp; Fig Lane"))
        assertTrue(html.contains("Leave at &lt;front&gt; desk &amp; ring"))
        assertTrue(html.contains("2026-08-09T08:30:45 UTC"))
        assertTrue(html.contains("USD 90.80"))
        assertTrue(html.contains("https://shop.pelissa.example/account/orders"))
        assertTrue(html.contains("View Stripe receipt"))
        assertTrue(html.contains("payment_intent=pi_123&amp;charge=ch_123"))
        assertFalse(html.contains("{{"))
        assertFalse(html.contains("}}"))
    }


    @Test
    fun `payment confirmation rejects a non HTTPS Stripe receipt URL before loading the order`() {
        assertFailsWith<IllegalArgumentException> {
            service.sendPaymentConfirmation(7, "http://pay.stripe.com/receipts/payment-7")
        }

        verify(orderRepository, never()).findById(7)
    }

    @Test
    fun `payment confirmation without a Stripe receipt omits receipt links`() {
        val order = OrderEntity(
            id = 8,
            orderNo = "PS-20260819-001",
            customerId = 13,
            status = OrderStatus.PAID,
            totalAmount = BigDecimal("0.00"),
            currency = "USD",
            shippingAddress = OrderShippingAddress(
                name = "No Receipt",
                phone = "+[REDACTED]",
                country = "US",
                city = "Seattle",
                address1 = "1 Test Lane",
            ),
            paidAt = Instant.parse("2026-08-19T13:14:00Z"),
        )
        val customer = User(
            id = 13,
            email = "no-receipt@example.test",
            username = "no-receipt",
            password = "[REDACTED]",
        )
        val message = MimeMessage(Session.getInstance(Properties()))
        `when`(orderRepository.findById(8)).thenReturn(Optional.of(order))
        `when`(userRepository.findById(13)).thenReturn(Optional.of(customer))
        `when`(orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(8)).thenReturn(emptyList())
        `when`(mailSender.createMimeMessage()).thenReturn(message)

        service.sendPaymentConfirmation(8, null)
        message.saveChanges()

        val parts = textParts(message.content)
        val plaintext = parts.first { it.startsWith("PELISSA payment received") }
        val html = parts.first { it.contains("<html") }
        assertFalse(plaintext.contains("View Stripe receipt"))
        assertFalse(html.contains("View Stripe receipt"))
        assertFalse(html.contains("{{stripe_receipt_link}}"))
        verify(mailSender).send(message)
    }

    @Test
    fun `refund emails include order and Stripe refund details`() {
        val order = OrderEntity(
            id = 30,
            orderNo = "PS-20260824-030",
            customerId = 31,
            itemsSubtotal = BigDecimal("25.00"),
            totalAmount = BigDecimal("25.00"),
            currency = "USD",
            paymentIntentId = "pi_123",
            stripeRefundId = "re_123",
            refundRequestedAt = LocalDateTime.parse("2026-08-24T10:20:00"),
            refundedAt = LocalDateTime.parse("2026-08-24T10:25:00"),
            refundReason = "Changed & mind",
            refundReasonDetail = "Please use <secure> handling",
        )
        val customer = User(
            id = 31,
            email = "refund@example.com",
            username = "refund-user",
            password = "password",
            firstName = "Ava &",
            lastName = "Refund",
        )
        val item = OrderItem(
            productId = 1,
            variantId = 1,
            sku = "REFUND-1",
            productSnapshot = """{"name":"Silk & Lace"}""",
            quantity = 1,
            lineTotal = BigDecimal("25.00"),
        )
        val refund = PaymentRefund(
            providerRefundId = "re_123",
            providerPaymentId = "pi_123",
            amount = PaymentAmount(BigDecimal("25.00"), "USD"),
            status = PaymentRefundStatus.SUCCEEDED,
        )
        val requestMessage = MimeMessage(Session.getInstance(Properties()))
        val confirmationMessage = MimeMessage(Session.getInstance(Properties()))
        `when`(orderRepository.findById(30)).thenReturn(Optional.of(order))
        `when`(userRepository.findById(31)).thenReturn(Optional.of(customer))
        `when`(orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(30)).thenReturn(listOf(item))
        `when`(mailSender.createMimeMessage()).thenReturn(requestMessage, confirmationMessage)

        service.sendRefundRequested(30, refund)
        service.sendRefundConfirmation(30, refund)
        requestMessage.saveChanges()
        confirmationMessage.saveChanges()

        val requestParts = textParts(requestMessage.content)
        val requestText = requestParts.first { it.startsWith("PELISSA refund request received") }
        val requestHtml = requestParts.first { it.contains("<html") }
        assertEquals("PELISSA | Refund request received · PS-20260824-030", requestMessage.subject)
        assertTrue(requestText.contains("Order total: USD 25.00"))
        assertTrue(requestText.contains("Refund ID: re_123"))
        assertTrue(requestHtml.contains("Ava &amp; Refund"))
        assertTrue(requestHtml.contains("Changed &amp; mind"))
        assertTrue(requestHtml.contains("Please use &lt;secure&gt; handling"))
        assertTrue(requestHtml.contains("USD 25.00"))

        val confirmationParts = textParts(confirmationMessage.content)
        val confirmationText = confirmationParts.first { it.startsWith("PELISSA refund confirmed") }
        assertEquals("PELISSA | Refund confirmed · PS-20260824-030", confirmationMessage.subject)
        assertTrue(confirmationText.contains("Refund status: Succeeded"))
        assertTrue(confirmationText.contains("Refund amount: USD 25.00"))
        assertTrue(confirmationText.contains("Confirmed at: 2026-08-24T10:25:00"))
        val confirmationHtml = confirmationParts.first { it.contains("<html") }
        assertTrue(confirmationHtml.contains("Confirmed at"))
        assertTrue(confirmationHtml.contains("2026-08-24T10:25:00"))
        verify(mailSender, org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.any(MimeMessage::class.java))
    }


    @Test
    fun `refund email rejects a refund bound to another payment`() {
        val order = OrderEntity(
            id = 32,
            orderNo = "PS-20260824-032",
            customerId = 33,
            paymentIntentId = "pi_expected",
            stripeRefundId = "re_expected",
            totalAmount = BigDecimal("10.00"),
        )
        val customer = User(id = 33, email = "refund@example.com", username = "refund-user", password = "password")
        val refund = PaymentRefund(
            providerRefundId = "re_expected",
            providerPaymentId = "pi_other",
            amount = PaymentAmount(BigDecimal("10.00"), "USD"),
            status = PaymentRefundStatus.SUCCEEDED,
        )
        `when`(orderRepository.findById(32)).thenReturn(Optional.of(order))
        `when`(userRepository.findById(33)).thenReturn(Optional.of(customer))

        assertFailsWith<IllegalArgumentException> { service.sendRefundConfirmation(32, refund) }
        org.mockito.Mockito.verifyNoInteractions(mailSender)
    }
    private fun textParts(content: Any?): List<String> = when (content) {
        is String -> listOf(content)
        is Multipart -> (0 until content.count).flatMap { index -> textParts(content.getBodyPart(index).content) }
        else -> emptyList()
    }
}
