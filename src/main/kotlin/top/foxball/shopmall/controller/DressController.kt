package top.foxball.shopmall.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.DressService
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.shared.Response as ApiResponse

/** 商品写入请求；标签 ID 独立传递，避免客户端直接构造持久化标签实体。 */
data class DressUpsertRequest(
    @field:Valid
    val dress: Dress = Dress(),
    @field:Size(max = 20)
    val tagIds: Set<Long> = emptySet(),
)

/** 消费者读取上架连衣裙，商城管理员维护全部连衣裙目录。 */
@RestController
@RequestMapping
class DressController(
    private val dressService: DressService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/dresses")
    fun listPublished(): ResponseEntity<ApiResponse> {
        data class Response(val dresses: List<DressResponse>)
        return builder.ok().data(Response(dressService.listPublished().map(Dress::toResponse))).build()
    }

    @GetMapping("/api/dresses/{id}")
    fun getPublished(@PathVariable id: Long): ResponseEntity<ApiResponse> {
        data class Response(val dress: DressResponse)
        val dress = dressService.getPublished(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(dress.toResponse())).build()
    }

    @GetMapping("/api/admin/dresses")
    fun listForAdmin(@AuthenticationPrincipal userId: Long): ResponseEntity<ApiResponse> {
        data class Response(val dresses: List<DressResponse>)
        adminAccessService.requireAdmin(userId)
        return builder.ok().data(Response(dressService.listForAdmin().map(Dress::toResponse))).build()
    }

    @GetMapping("/api/admin/dresses/{id}")
    fun getForAdmin(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val dress: DressResponse)
        adminAccessService.requireAdmin(userId)
        val dress = dressService.getForAdmin(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(dress.toResponse())).build()
    }

    @PostMapping("/api/admin/dresses")
    fun create(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: DressUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val dress: DressResponse)
        adminAccessService.requireAdmin(userId)
        val dress = dressService.create(request.dress, request.tagIds)
        return builder.status(HttpStatus.CREATED).data(Response(dress.toResponse())).build()
    }

    @PutMapping("/api/admin/dresses/{id}")
    fun update(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: DressUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val dress: DressResponse)
        adminAccessService.requireAdmin(userId)
        val dress = dressService.update(id, request.dress, request.tagIds) ?: return builder.notFound().build()
        return builder.ok().data(Response(dress.toResponse())).build()
    }

    @DeleteMapping("/api/admin/dresses/{id}")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val id: Long, val deleted: Boolean)
        adminAccessService.requireAdmin(userId)
        if (!dressService.delete(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }
}
