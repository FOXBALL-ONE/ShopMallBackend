package top.foxball.shopmall.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import top.foxball.shopmall.config.OrderMailProperties
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.OrderMailService
import top.foxball.shopmall.service.payMent.PaymentRefund
import java.math.RoundingMode
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.web.util.HtmlUtils

/** 以订单、商品及收货地址快照生成并投递付款成功确认邮件。 */
@Service
class OrderMailServiceImpl(
    private val mailSender: JavaMailSender,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper,
    private val properties: OrderMailProperties,
    @Value("\${spring.mail.username:}") private val mailUsername: String,
    @Value("\${stripe.checkout.storefront-base-url:http://localhost:3000}") private val storefrontBaseUrl: String,
) : OrderMailService {

    private val paymentConfirmationTemplate = ClassPathResource("templates/mail/order-payment-confirmation.html")
        .inputStream
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }
    private val refundRequestedTemplate = ClassPathResource("templates/mail/order-refund-requested.html")
        .inputStream
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }
    private val refundConfirmationTemplate = ClassPathResource("templates/mail/order-refund-confirmation.html")
        .inputStream
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }

    override fun sendPaymentConfirmation(orderId: Long, stripeReceiptUrl: String?) {
        if (stripeReceiptUrl != null) {
            val receiptUri = try {
                URI(stripeReceiptUrl)
            } catch (ex: Exception) {
                throw IllegalArgumentException("Stripe receipt URL is invalid", ex)
            }
            require(
                receiptUri.isAbsolute && receiptUri.host != null && receiptUri.scheme.equals("https", ignoreCase = true),
            ) {
                "Stripe receipt URL must be an absolute HTTPS URL"
            }
        }
        val order = orderRepository.findById(orderId).orElseThrow {
            IllegalStateException("Cannot send payment confirmation for missing order $orderId")
        }
        val customer = userRepository.findById(order.customerId).orElseThrow {
            IllegalStateException("Cannot send payment confirmation for missing customer ${order.customerId}")
        }
        val orderItems = orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(orderId)

        data class EmailItem(
            val name: String,
            val details: String?,
            val quantity: Int,
            val lineTotal: String,
        )

        fun snapshotText(item: OrderItem, key: String): String? = runCatching {
            objectMapper.readTree(item.productSnapshot).get(key)?.asString()?.trim()?.takeIf(String::isNotBlank)
        }.getOrNull()

        fun snapshotVariantAttribute(item: OrderItem, key: String): String? = runCatching {
            objectMapper.readTree(item.productSnapshot).get("variantAttributes")?.get(key)?.asString()?.trim()?.takeIf(String::isNotBlank)
        }.getOrNull()

        fun displayValue(value: String): String = value
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        fun money(amount: java.math.BigDecimal): String =
            "${order.currency} ${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

        val items = orderItems.map { item ->
            val color = snapshotText(item, "color")
            val size = snapshotText(item, "size")?.let(::displayValue)
            val topSize = snapshotVariantAttribute(item, "top_size")?.let(::displayValue)
            val bottomSize = snapshotVariantAttribute(item, "bottom_size")?.let(::displayValue)
            EmailItem(
                name = snapshotText(item, "name") ?: "Pelissa piece #${item.productId}",
                details = listOfNotNull(
                    color?.let { "Color: $it" },
                    size?.let { "Size: $it" },
                    topSize?.let { "Top: $it" },
                    bottomSize?.let { "Bottom: $it" },
                    "SKU: ${item.sku}",
                ).takeIf(List<String>::isNotEmpty)?.joinToString(" · "),
                quantity = item.quantity,
                lineTotal = money(item.lineTotal),
            )
        }
        val shippingAddress = order.shippingAddress
        val shippingAddressLines = listOfNotNull(
            shippingAddress.company?.trim()?.takeIf(String::isNotBlank),
            shippingAddress.name.trim().takeIf(String::isNotBlank),
            shippingAddress.address1.trim().takeIf(String::isNotBlank),
            shippingAddress.address2?.trim()?.takeIf(String::isNotBlank),
            listOfNotNull(
                shippingAddress.district?.trim()?.takeIf(String::isNotBlank),
                shippingAddress.city.trim().takeIf(String::isNotBlank),
                shippingAddress.stateOrProvince?.trim()?.takeIf(String::isNotBlank),
                shippingAddress.postalCode?.trim()?.takeIf(String::isNotBlank),
            ).joinToString(", ").takeIf(String::isNotBlank),
            shippingAddress.country.trim().takeIf(String::isNotBlank),
            shippingAddress.phone.trim().takeIf(String::isNotBlank),
        )
        val customerName = listOf(customer.firstName, customer.lastName)
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { customer.username }
        val paidAt = order.paidAt?.let {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(it, ZoneOffset.UTC)) + " UTC"
        } ?: "Payment received"
        val notes = listOfNotNull(
            order.clientMessage?.trim()?.takeIf(String::isNotBlank)?.let { "Order note: $it" },
            shippingAddress.deliveryInstructions?.trim()?.takeIf(String::isNotBlank)?.let { "Delivery note: $it" },
        )
        val itemRows = items.joinToString("") {
            """
            <tr>
              <td style="padding:16px 0; border-bottom:1px solid #ded4d3; font-family:Arial, sans-serif; vertical-align:top;">
                <p style="margin:0; font-size:14px; font-weight:700; line-height:20px; color:#241d21;">${HtmlUtils.htmlEscape(it.name)}</p>
                ${it.details?.let { details -> "<p style=\"margin:3px 0 0; font-size:12px; line-height:18px; color:#756a70;\">${HtmlUtils.htmlEscape(details)}</p>" }.orEmpty()}
              </td>
              <td align="center" style="width:48px; padding:16px 8px; border-bottom:1px solid #ded4d3; font-family:Courier New, monospace; font-size:11px; line-height:20px; color:#75636a; vertical-align:top;">×${it.quantity}</td>
              <td align="right" style="width:112px; padding:16px 0 16px 8px; border-bottom:1px solid #ded4d3; font-family:Arial, sans-serif; font-size:13px; font-weight:700; line-height:20px; color:#241d21; vertical-align:top; white-space:nowrap;">${HtmlUtils.htmlEscape(it.lineTotal)}</td>
            </tr>
            """.trimIndent()
        }
        val orderNotes = if (notes.isEmpty()) "" else {
            """
            <tr>
              <td style="padding:22px 0 0;">
                <p style="margin:0 0 7px; font-family:Courier New, monospace; font-size:9px; font-weight:700; letter-spacing:1.1px; line-height:14px; color:#9a4055; text-transform:uppercase;">A note for delivery</p>
                <p style="margin:0; font-family:Arial, sans-serif; font-size:12px; line-height:19px; color:#756a70;">${notes.joinToString("<br>") { HtmlUtils.htmlEscape(it) }}</p>
              </td>
            </tr>
            """.trimIndent()
        }
        val ordersUrl = storefrontBaseUrl.trimEnd('/') + "/account/orders"
        val receiptLink = stripeReceiptUrl?.let {
            """
            <tr>
              <td style="padding-top:12px;">
                <a href="${HtmlUtils.htmlEscape(it)}" target="_blank" rel="noopener noreferrer" style="font-family:Arial, sans-serif; font-size:12px; line-height:18px; color:#9a4055; text-decoration:underline;">View Stripe receipt&nbsp; ↗</a>
              </td>
            </tr>
            """.trimIndent()
        }.orEmpty()
        var html = paymentConfirmationTemplate
        mapOf(
            "{{customer_name}}" to HtmlUtils.htmlEscape(customerName),
            "{{order_no}}" to HtmlUtils.htmlEscape(order.orderNo),
            "{{paid_at}}" to HtmlUtils.htmlEscape(paidAt),
            "{{item_count}}" to items.sumOf(EmailItem::quantity).toString(),
            "{{order_items}}" to itemRows,
            "{{items_subtotal}}" to HtmlUtils.htmlEscape(money(order.itemsSubtotal)),
            "{{shipping_fee}}" to HtmlUtils.htmlEscape(money(order.shippingFee)),
            "{{tax_amount}}" to HtmlUtils.htmlEscape(money(order.taxAmount)),
            "{{discount_amount}}" to HtmlUtils.htmlEscape(money(order.discountAmount)),
            "{{total_amount}}" to HtmlUtils.htmlEscape(money(order.totalAmount)),
            "{{shipping_address}}" to shippingAddressLines.joinToString("<br>") { HtmlUtils.htmlEscape(it) },
            "{{order_notes}}" to orderNotes,
            "{{orders_url}}" to HtmlUtils.htmlEscape(ordersUrl),
            "{{stripe_receipt_link}}" to receiptLink,
        ).forEach { (token, value) -> html = html.replace(token, value) }
        val text = buildString {
            appendLine("PELISSA payment received")
            appendLine()
            appendLine("Hello $customerName,")
            appendLine("We received your payment for order ${order.orderNo}.")
            appendLine("Paid at: $paidAt")
            appendLine()
            appendLine("ORDER SUMMARY")
            items.forEach {
                appendLine("- ${it.name}${it.details?.let { details -> " ($details)" }.orEmpty()} ×${it.quantity}: ${it.lineTotal}")
            }
            appendLine("Subtotal: ${money(order.itemsSubtotal)}")
            appendLine("Shipping: ${money(order.shippingFee)}")
            appendLine("Tax: ${money(order.taxAmount)}")
            appendLine("Discount: ${money(order.discountAmount)}")
            appendLine("Total paid: ${money(order.totalAmount)}")
            appendLine()
            appendLine("SHIP TO")
            shippingAddressLines.forEach(::appendLine)
            if (notes.isNotEmpty()) {
                appendLine()
                notes.forEach(::appendLine)
            }
            appendLine()
            appendLine("View your order: $ordersUrl")
            stripeReceiptUrl?.let { appendLine("View Stripe receipt: $it") }
        }

        try {
            val message = mailSender.createMimeMessage()
            MimeMessageHelper(message, true, "UTF-8").apply {
                setTo(customer.email)
                setFrom(properties.from.ifBlank { mailUsername })
                setSubject("${properties.subjectPrefix} | Payment received · ${order.orderNo}")
                setText(text, html)
            }
            mailSender.send(message)
        } catch (ex: Exception) {
            throw IllegalStateException("Unable to send payment confirmation for order ${order.orderNo}", ex)
        }
    }

    override fun sendRefundRequested(orderId: Long, refund: PaymentRefund) {
        sendRefundMail(orderId, refund, refundRequestedTemplate, confirmation = false)
    }

    override fun sendRefundConfirmation(orderId: Long, refund: PaymentRefund) {
        sendRefundMail(orderId, refund, refundConfirmationTemplate, confirmation = true)
    }

    private fun sendRefundMail(
        orderId: Long,
        refund: PaymentRefund,
        template: String,
        confirmation: Boolean,
    ) {
        val order = orderRepository.findById(orderId).orElseThrow {
            IllegalStateException("Cannot send refund email for missing order $orderId")
        }
        val customer = userRepository.findById(order.customerId).orElseThrow {
            IllegalStateException("Cannot send refund email for missing customer ${order.customerId}")
        }
        order.paymentIntentId?.let { expectedPaymentIntentId ->
            require(refund.providerPaymentId == expectedPaymentIntentId) {
                "Refund ${refund.providerRefundId} belongs to PaymentIntent ${refund.providerPaymentId}, " +
                    "expected $expectedPaymentIntentId"
            }
        }
        order.stripeRefundId?.let { expectedRefundId ->
            require(refund.providerRefundId == expectedRefundId) {
                "Refund ${refund.providerRefundId} does not match order ${order.orderNo} refund $expectedRefundId"
            }
        }
        val customerName = listOf(customer.firstName, customer.lastName)
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { customer.username }
        val money = { amount: java.math.BigDecimal, currency: String ->
            "$currency ${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
        }
        val orderTotal = money(order.totalAmount, order.currency)
        val refundAmount = money(refund.amount.value, refund.amount.currency)
        val refundStatus = refund.status.name
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val requestedAt = order.refundRequestedAt
            ?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ?.takeIf(String::isNotBlank)
            ?: "Not available"
        val refundedAt = order.refundedAt
            ?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ?.takeIf(String::isNotBlank)
            ?: "Not available"
        val reason = listOfNotNull(
            order.refundReason?.trim()?.takeIf { it.isNotBlank() },
            order.refundReasonDetail?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(" - ").ifBlank { "Not provided" }
        val itemSummary = if (confirmation) "" else {
            orderItemRepository.findAllByOrder_IdOrderByVariantIdAsc(orderId).joinToString("<br>") { item ->
                val name = runCatching {
                    objectMapper.readTree(item.productSnapshot).get("name")?.asString()?.trim()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "ShopMall item"
                "${HtmlUtils.htmlEscape(name)} &times;${item.quantity} &middot; ${HtmlUtils.htmlEscape(money(item.lineTotal, order.currency))}"
            }.ifBlank { "No item details available" }
        }
        var html = template
        mapOf(
            "{{customer_name}}" to HtmlUtils.htmlEscape(customerName),
            "{{order_no}}" to HtmlUtils.htmlEscape(order.orderNo),
            "{{order_total}}" to HtmlUtils.htmlEscape(orderTotal),
            "{{refund_id}}" to HtmlUtils.htmlEscape(refund.providerRefundId),
            "{{refund_amount}}" to HtmlUtils.htmlEscape(refundAmount),
            "{{refund_status}}" to HtmlUtils.htmlEscape(refundStatus),
            "{{requested_at}}" to HtmlUtils.htmlEscape(requestedAt),
            "{{refunded_at}}" to HtmlUtils.htmlEscape(refundedAt),
            "{{refund_reason}}" to HtmlUtils.htmlEscape(reason),
            "{{item_summary}}" to itemSummary,
        ).forEach { (token, value) -> html = html.replace(token, value) }
        val text = buildString {
            appendLine(if (confirmation) "PELISSA refund confirmed" else "PELISSA refund request received")
            appendLine()
            appendLine("Hello $customerName,")
            appendLine(
                if (confirmation) {
                    "Stripe confirmed a refund for order ${order.orderNo}."
                } else {
                    "We sent your refund request for order ${order.orderNo} to Stripe."
                },
            )
            appendLine()
            if (!confirmation) {
                appendLine("Order total: $orderTotal")
                if (itemSummary.isNotBlank()) {
                    appendLine("Items: ${HtmlUtils.htmlUnescape(itemSummary.replace("<br>", "; "))}")
                }
                if (requestedAt.isNotBlank()) appendLine("Requested at: $requestedAt")
                if (reason.isNotBlank()) appendLine("Reason: $reason")
            }
            appendLine("Refund ID: ${refund.providerRefundId}")
            appendLine("Refund amount: $refundAmount")
            appendLine("Refund status: $refundStatus")
            if (refundedAt.isNotBlank()) appendLine("Confirmed at: $refundedAt")
        }
        try {
            val message = mailSender.createMimeMessage()
            MimeMessageHelper(message, true, "UTF-8").apply {
                setTo(customer.email)
                setFrom(properties.from.ifBlank { mailUsername })
                setSubject(
                    if (confirmation) {
                        "${properties.subjectPrefix} | Refund confirmed · ${order.orderNo}"
                    } else {
                        "${properties.subjectPrefix} | Refund request received · ${order.orderNo}"
                    },
                )
                setText(text, html)
            }
            mailSender.send(message)
        } catch (ex: Exception) {
            throw IllegalStateException("Unable to send refund email for order ${order.orderNo}", ex)
        }
    }
}
