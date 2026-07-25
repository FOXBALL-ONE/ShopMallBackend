package top.foxball.shopmall.controller

import org.springframework.data.domain.Page
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderItem
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.service.OrderPaymentView
import top.foxball.shopmall.service.OrderView
import java.math.BigDecimal
import java.time.Instant

data class OrderItemResponse(
    val id: Long,
    val productId: Long,
    val productSnapshot: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
    val lineTotal: BigDecimal,
    val createdAt: Instant?,
)

data class OrderShippingAddressResponse(
    val name: String,
    val phone: String,
    val country: String,
    val stateOrProvince: String?,
    val city: String,
    val district: String?,
    val postalCode: String?,
    val address1: String,
    val address2: String?,
    val company: String?,
    val deliveryInstructions: String?,
)

data class OrderResponse(
    val id: Long,
    val orderNo: String,
    val customerId: Long,
    val status: OrderStatus,
    val itemsSubtotal: BigDecimal,
    val shippingFee: BigDecimal,
    val taxAmount: BigDecimal,
    val discountAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val currency: String,
    val paymentIntentId: String?,
    val clientSecret: String?,
    val shippingAddress: OrderShippingAddressResponse,
    val clientMessage: String?,
    val expiresAt: Instant?,
    val paidAt: Instant?,
    val cancelledAt: Instant?,
    val shippedAt: Instant?,
    val deliveredAt: Instant?,
    val cancelReason: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val items: List<OrderItemResponse>,
)

data class OrderPaymentResponse(
    val orderNo: String,
    val status: OrderStatus,
    val clientSecret: String?,
    val expiresAt: Instant?,
)

data class OrderPageResponse(
    val orders: List<OrderResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun OrderItem.toResponse(): OrderItemResponse = OrderItemResponse(
    id = requireNotNull(id),
    productId = productId,
    productSnapshot = productSnapshot,
    unitPrice = unitPrice,
    quantity = quantity,
    lineTotal = lineTotal,
    createdAt = createdAt,
)

fun OrderShippingAddress.toResponse(): OrderShippingAddressResponse = OrderShippingAddressResponse(
    name = name,
    phone = phone,
    country = country,
    stateOrProvince = stateOrProvince,
    city = city,
    district = district,
    postalCode = postalCode,
    address1 = address1,
    address2 = address2,
    company = company,
    deliveryInstructions = deliveryInstructions,
)

fun OrderEntity.toResponse(
    items: List<OrderItem>,
    clientSecret: String? = null,
): OrderResponse = OrderResponse(
    id = requireNotNull(id),
    orderNo = orderNo,
    customerId = customerId,
    status = status,
    itemsSubtotal = itemsSubtotal,
    shippingFee = shippingFee,
    taxAmount = taxAmount,
    discountAmount = discountAmount,
    totalAmount = totalAmount,
    currency = currency,
    paymentIntentId = paymentIntentId,
    clientSecret = clientSecret,
    shippingAddress = shippingAddress.toResponse(),
    clientMessage = clientMessage,
    expiresAt = expiresAt,
    paidAt = paidAt,
    cancelledAt = cancelledAt,
    shippedAt = shippedAt,
    deliveredAt = deliveredAt,
    cancelReason = cancelReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
    items = items.map(OrderItem::toResponse),
)

fun OrderView.toResponse(): OrderResponse = order.toResponse(items, clientSecret)

fun OrderPaymentView.toResponse(): OrderPaymentResponse = OrderPaymentResponse(
    orderNo = orderNo,
    status = status,
    clientSecret = clientSecret,
    expiresAt = expiresAt,
)

fun Page<OrderView>.toPageResponse(): OrderPageResponse = OrderPageResponse(
    orders = content.map(OrderView::toResponse),
    page = number,
    size = size,
    totalElements = totalElements,
    totalPages = totalPages,
)
