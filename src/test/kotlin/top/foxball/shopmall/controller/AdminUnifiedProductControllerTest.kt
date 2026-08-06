package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.controller.admin.AdminProductController
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminProductService
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal

class AdminUnifiedProductControllerTest {
    private lateinit var adminProductService: AdminProductService
    private lateinit var adminAccessService: AdminAccessService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        adminProductService = mock(AdminProductService::class.java)
        adminAccessService = mock(AdminAccessService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(
            AdminProductController(adminProductService, adminAccessService, ResponseBuilder()),
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
    fun `unified product list exposes subtype fields real tags and pagination`() {
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
            warehouseVolume = 4
            salesVolume = 12
            status = Product.Status.ACTIVE
            tags = linkedSetOf(Tag(id = 7L, name = "Summer", color = "#22AA66", sortOrder = 2))
        }
        val result = PageImpl<Product>(listOf(dress), PageRequest.of(1, 2), 3)
        `when`(
            adminProductService.list(
                AdminProductService.ProductType.DRESS,
                Product.Status.ACTIVE,
                "summer",
                true,
                5,
                AdminProductService.SortBy.STOCK,
                true,
                1,
                2,
            ),
        ).thenReturn(result)

        mockMvc.perform(
            get("/admin/api/products")
                .param("product_type", "DRESS")
                .param("status", "ACTIVE")
                .param("keyword", "summer")
                .param("low_stock", "true")
                .param("low_stock_threshold", "5")
                .param("sort_by", "STOCK")
                .param("ascending", "true")
                .param("page", "2")
                .param("size", "2"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.list[0].product_type").value("DRESS"))
            .andExpect(jsonPath("$.data.list[0].size").value("M"))
            .andExpect(jsonPath("$.data.list[0].sleeve_type").value("SHORT"))
            .andExpect(jsonPath("$.data.list[0].tags[0].name").value("Summer"))
            .andExpect(jsonPath("$.data.list[0].tags[0].color").value("#22AA66"))
            .andExpect(jsonPath("$.data.pagination.page").value(2))
            .andExpect(jsonPath("$.data.pagination.total_items").value(3))
            .andExpect(jsonPath("$.data.pagination.total_pages").value(2))

        verify(adminAccessService).requireAdmin(99L)
    }

    @Test
    fun `stock endpoint returns atomically adjusted inventory`() {
        `when`(adminProductService.adjustStock(10L, -3)).thenReturn(7)

        mockMvc.perform(
            patch("/admin/api/products/10/stock")
                .param("adjustment", "-3"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(10))
            .andExpect(jsonPath("$.data.adjustment").value(-3))
            .andExpect(jsonPath("$.data.warehouse_volume").value(7))

        verify(adminAccessService).requireAdmin(99L)
    }

    @Test
    fun `permanent delete endpoint delegates sorted ids to guarded service operation`() {
        `when`(adminProductService.permanentlyDelete(listOf(3L, 10L))).thenReturn(2)

        mockMvc.perform(
            delete("/admin/api/products/batch/permanent")
                .param("ids", "10", "3"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.ids[0]").value(3))
            .andExpect(jsonPath("$.data.ids[1]").value(10))
            .andExpect(jsonPath("$.data.deleted").value(2))

        verify(adminAccessService).requireAdmin(99L)
        verify(adminProductService).permanentlyDelete(listOf(3L, 10L))
    }
}
