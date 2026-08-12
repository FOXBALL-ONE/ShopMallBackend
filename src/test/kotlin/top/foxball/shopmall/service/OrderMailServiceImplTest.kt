package top.foxball.shopmall.service

import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.mockito.Mockito.mock
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
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
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

        service.sendPaymentConfirmation(7)
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
        assertTrue(html.contains("Silk &amp; Lace"))
        assertTrue(html.contains("Rose &amp; Ivory"))
        assertTrue(html.contains("Atelier &lt;North&gt;"))
        assertTrue(html.contains("45 &amp; Fig Lane"))
        assertTrue(html.contains("Leave at &lt;front&gt; desk &amp; ring"))
        assertTrue(html.contains("2026-08-09T08:30:45 UTC"))
        assertTrue(html.contains("USD 90.80"))
        assertTrue(html.contains("https://shop.pelissa.example/account/orders"))
        assertFalse(html.contains("{{"))
        assertFalse(html.contains("}}"))
    }

    private fun textParts(content: Any?): List<String> = when (content) {
        is String -> listOf(content)
        is Multipart -> (0 until content.count).flatMap { index -> textParts(content.getBodyPart(index).content) }
        else -> emptyList()
    }
}
