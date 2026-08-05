package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.controller.admin.AdminDressController
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.DressService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

class AdminProductControllerTest {
    private lateinit var dressService: DressService
    private lateinit var adminAccessService: AdminAccessService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        dressService = mock(DressService::class.java)
        adminAccessService = mock(AdminAccessService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminDressController(dressService, adminAccessService, ResponseBuilder()),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(99L, null)
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `admin product list exposes complete editable snake case fields`() {
        val createdAt = LocalDateTime.parse("2026-08-05T10:00:00")
        val dress = Dress(
            size = Dress.Size.M,
            length = Dress.Length.MIDI,
            silhouette = Dress.Silhouette.WRAP,
            neckline = Dress.Neckline.V_NECK,
            sleeveType = Dress.SleeveType.SHORT,
            fabric = "linen",
        ).apply {
            id = 10L
            name = "Summer dress"
            color = "green"
            price = BigDecimal("49.90")
            warehouseVolume = 8
            salesVolume = 12
            status = Product.Status.INACTIVE
            highlight = mutableListOf("lightweight")
            images = mutableListOf("https://files.example.test/api/product-images/image")
            fitSense = "regular"
            description = "Product description"
            designAndExtras = mutableListOf("belt")
            careInstructions = mutableListOf("hand wash")
            score = 4.5f
            tags = linkedSetOf(Tag(id = 7L, name = "summer"))
            this.createdAt = createdAt
            updatedAt = createdAt.plusHours(1)
        }
        `when`(dressService.listForAdmin()).thenReturn(listOf(dress))

        mockMvc.perform(get("/admin/api/dresses"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.list[0].id").value(10))
            .andExpect(jsonPath("$.data.list[0].sales_volume").value(12))
            .andExpect(jsonPath("$.data.list[0].sleeve_type").value("SHORT"))
            .andExpect(jsonPath("$.data.list[0].images[0]").value(dress.images[0]))
            .andExpect(jsonPath("$.data.list[0].design_and_extras[0]").value("belt"))
            .andExpect(jsonPath("$.data.list[0].care_instructions[0]").value("hand wash"))
            .andExpect(jsonPath("$.data.list[0].tag_ids[0]").value(7))
            .andExpect(jsonPath("$.data.list[0].created_at").value("2026-08-05T10:00:00"))

        verify(adminAccessService).requireAdmin(99L)
    }
}
