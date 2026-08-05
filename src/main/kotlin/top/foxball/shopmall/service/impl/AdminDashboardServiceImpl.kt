package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderStatus
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus
import top.foxball.shopmall.repository.OrderRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ShipmentRepository
import top.foxball.shopmall.repository.SupportTicketRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminDashboardService
import top.foxball.shopmall.service.AdminDashboardSummary

@Service
@Transactional(readOnly = true)
class AdminDashboardServiceImpl(
    private val orderRepository: OrderRepository,
    private val shipmentRepository: ShipmentRepository,
    private val supportTicketRepository: SupportTicketRepository,
    private val productRepository: ProductRepository,
    private val adminAccessService: AdminAccessService,
) : AdminDashboardService {
    override fun summary(adminId: Long, lowStockThreshold: Int): AdminDashboardSummary {
        adminAccessService.requireAdmin(adminId)
        return AdminDashboardSummary(
            pendingPaymentOrders = orderRepository.countByStatus(OrderStatus.PENDING_PAYMENT),
            paidOrders = orderRepository.countByStatus(OrderStatus.PAID),
            shippedOrders = orderRepository.countByStatus(OrderStatus.SHIPPED),
            deliveredOrders = orderRepository.countByStatus(OrderStatus.DELIVERED),
            completedOrders = orderRepository.countByStatus(OrderStatus.COMPLETED),
            cancelledOrders = orderRepository.countByStatus(OrderStatus.CANCELLED),
            labelPendingShipments = shipmentRepository.countByStatus(ShipmentStatus.LABEL_PENDING),
            labelCreatedShipments = shipmentRepository.countByStatus(ShipmentStatus.LABEL_CREATED),
            cancelPendingShipments = shipmentRepository.countByStatus(ShipmentStatus.CANCEL_PENDING),
            inTransitShipments = shipmentRepository.countByStatus(ShipmentStatus.IN_TRANSIT),
            outForDeliveryShipments = shipmentRepository.countByStatus(ShipmentStatus.OUT_FOR_DELIVERY),
            deliveredShipments = shipmentRepository.countByStatus(ShipmentStatus.DELIVERED),
            cancelledShipments = shipmentRepository.countByStatus(ShipmentStatus.CANCELLED),
            shipmentErrors = shipmentRepository.countByLastTrackErrorIsNotNull(),
            openTickets = supportTicketRepository.countByStatus(SupportTicketStatus.OPEN),
            inProgressTickets = supportTicketRepository.countByStatus(SupportTicketStatus.IN_PROGRESS),
            highPriorityTickets = supportTicketRepository.countByPriorityAndStatusIn(
                SupportTicketPriority.HIGH,
                listOf(SupportTicketStatus.OPEN, SupportTicketStatus.IN_PROGRESS),
            ),
            activeProducts = productRepository.countByStatus(Product.Status.ACTIVE),
            inactiveProducts = productRepository.countByStatus(Product.Status.INACTIVE),
            deletedProducts = productRepository.countByStatus(Product.Status.DELETED),
            lowStockProducts = productRepository.countByWarehouseVolumeLessThanEqualAndStatus(
                lowStockThreshold,
                Product.Status.ACTIVE,
            ),
        )
    }
}
