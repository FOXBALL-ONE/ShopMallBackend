package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.NormalizedTrackingStatus
import top.foxball.shopmall.entity.jdbc.Shipment
import top.foxball.shopmall.entity.jdbc.ShipmentItem
import top.foxball.shopmall.entity.jdbc.ShipmentStatus
import top.foxball.shopmall.entity.jdbc.ShipmentTrack
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.controller.admin.AdminShipmentController
import top.foxball.shopmall.controller.admin.AdminShipmentQueryController
import top.foxball.shopmall.service.AdminShipmentQuery
import top.foxball.shopmall.service.ShipmentDetails
import top.foxball.shopmall.service.ShipmentService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.Instant

class ShipmentControllerTest {
    private lateinit var shipmentService: ShipmentService
    private lateinit var userService: UserService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        shipmentService = mock(ShipmentService::class.java)
        userService = mock(UserService::class.java)
        `when`(userService.getUsernameById(99)).thenReturn("admin")
        `when`(userService.getUsernamesByIds(anyList())).thenReturn(mapOf(99L to "admin"))
        mockMvc = MockMvcBuilders.standaloneSetup(
            ShipmentController(shipmentService, ResponseBuilder()),
            AdminShipmentController(shipmentService, userService, ResponseBuilder()),
            AdminShipmentQueryController(shipmentService, userService, ResponseBuilder()),
        )
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `create shipment binds snake case parameters and builds local response`() {
        authenticate(99L)
        val details = shipmentDetails()
        `when`(
            shipmentService.createShipment(
                orderNo = "ORD-1",
                carrierCode = CarrierCode.MANUAL,
                trackingNo = "TRACK-1",
                orderItemIds = listOf(11L),
                quantities = listOf(2),
                note = "front desk",
                adminId = 99L,
                idempotencyKey = "create-1",
            ),
        ).thenReturn(details)

        mockMvc.perform(
            post("/admin/api/orders/ORD-1/shipments")
                .header("Idempotency-Key", "create-1")
                .param("carrier_code", "MANUAL")
                .param("tracking_no", "TRACK-1")
                .param("order_item_ids", "11")
                .param("quantities", "2")
                .param("note", "front desk"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.shipment.shipment_no").value("SHP-1"))
            .andExpect(jsonPath("$.data.shipment.order_no").value("ORD-1"))
            .andExpect(jsonPath("$.data.shipment.items[0].order_item_id").value(11))
            .andExpect(jsonPath("$.data.shipment.tracks[0].normalized_status").value("IN_TRANSIT"))
            .andExpect(jsonPath("$.data.carrier_label_url").value("https://labels.example/SHP-1"))

        verify(shipmentService).createShipment(
            orderNo = "ORD-1",
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-1",
            orderItemIds = listOf(11L),
            quantities = listOf(2),
            note = "front desk",
            adminId = 99L,
            idempotencyKey = "create-1",
        )
    }

    @Test
    fun `customer shipment list is wrapped in list response`() {
        authenticate(7L)
        `when`(shipmentService.listCustomer("ORD-1", 7L)).thenReturn(listOf(shipmentDetails()))

        mockMvc.perform(get("/api/orders/ORD-1/shipments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.list[0].shipment_no").value("SHP-1"))
            .andExpect(jsonPath("$.data.list[0].tracking_no").value("TRACK-1"))

        verify(shipmentService).listCustomer("ORD-1", 7L)
    }

    @Test
    fun `customer confirms shipment delivery with idempotency key`() {
        authenticate(7L)
        val details = shipmentDetails()
        details.shipment.status = ShipmentStatus.DELIVERED
        `when`(shipmentService.markCustomerDelivered("ORD-1", "SHP-1", 7L, "customer-1")).thenReturn(details)

        mockMvc.perform(
            post("/api/orders/ORD-1/shipments/SHP-1/delivered")
                .header("Idempotency-Key", "customer-1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.shipment_no").value("SHP-1"))
            .andExpect(jsonPath("$.data.order_no").value("ORD-1"))
            .andExpect(jsonPath("$.data.status").value("DELIVERED"))
            .andExpect(jsonPath("$.data.tracks[0].normalized_status").value("IN_TRANSIT"))

        verify(shipmentService).markCustomerDelivered("ORD-1", "SHP-1", 7L, "customer-1")
    }

    @Test
    fun `admin global shipment list forwards filters and pagination`() {
        authenticate(99L)
        val query = AdminShipmentQuery(
            page = 0,
            size = 10,
            status = ShipmentStatus.IN_TRANSIT,
            carrier = CarrierCode.MANUAL,
            orderNo = "ORD-1",
            trackingNo = "TRACK",
            hasError = true,
        )
        `when`(shipmentService.listAdmin(99L, query)).thenReturn(
            PageImpl(listOf(shipmentDetails()), PageRequest.of(0, 10), 11),
        )

        mockMvc.perform(
            get("/admin/api/shipments")
                .param("page", "1")
                .param("size", "10")
                .param("status", "IN_TRANSIT")
                .param("carrier", "manual")
                .param("order_no", "ORD-1")
                .param("tracking_no", "TRACK")
                .param("has_error", "true"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.list[0].shipment.shipment_no").value("SHP-1"))
            .andExpect(jsonPath("$.data.list[0].created_by_username").value("admin"))
            .andExpect(jsonPath("$.data.pagination.total_items").value(11))
            .andExpect(jsonPath("$.data.pagination.total_pages").value(2))

        verify(shipmentService).listAdmin(99L, query)
    }

    @Test
    fun `admin shipment delete uses separate logical and permanent endpoints`() {
        authenticate(99L)
        val deleted = Shipment(
            id = 21L,
            shipmentNo = "SHP-1",
            orderId = 31L,
            status = ShipmentStatus.DELETED,
            createdBy = 99L,
        )
        `when`(shipmentService.deleteShipment("SHP-1", 99L)).thenReturn(deleted)

        mockMvc.perform(delete("/admin/api/shipments/SHP-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.shipment_no").value("SHP-1"))
            .andExpect(jsonPath("$.data.status").value("DELETED"))

        mockMvc.perform(delete("/admin/api/shipments/SHP-1/permanent"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.shipment_no").value("SHP-1"))
            .andExpect(jsonPath("$.data.physically_deleted").value(true))

        verify(shipmentService).deleteShipment("SHP-1", 99L)
        verify(shipmentService).permanentlyDeleteShipment("SHP-1", 99L)
    }

    private fun authenticate(userId: Long) {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(userId, null)
    }

    private fun shipmentDetails(): ShipmentDetails {
        val shipment = Shipment(
            id = 21L,
            shipmentNo = "SHP-1",
            orderId = 31L,
            carrierCode = CarrierCode.MANUAL,
            trackingNo = "TRACK-1",
            status = ShipmentStatus.IN_TRANSIT,
            trackingUrl = "https://tracking.example/TRACK-1",
            carrierLabelUrl = "https://labels.example/SHP-1",
            createdBy = 99L,
            note = "front desk",
        )
        return ShipmentDetails(
            shipment = shipment,
            orderNo = "ORD-1",
            items = listOf(
                ShipmentItem(
                    id = 41L,
                    shipment = shipment,
                    orderItemId = 11L,
                    orderItemSnapshot = "{}",
                    quantity = 2,
                ),
            ),
            tracks = listOf(
                ShipmentTrack(
                    id = 51L,
                    shipment = shipment,
                    carrierEventId = "event-1",
                    statusCode = "IN_TRANSIT",
                    normalizedStatus = NormalizedTrackingStatus.IN_TRANSIT,
                    source = TrackSource.MANUAL,
                    occurredAt = Instant.parse("2026-07-26T12:00:00Z"),
                ),
            ),
        )
    }
}
