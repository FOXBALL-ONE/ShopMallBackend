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
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.CoverUpService
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.shared.Response as ApiResponse

/** 商品写入请求；标签 ID 独立传递，避免客户端直接构造持久化标签实体。 */
data class CoverUpUpsertRequest(
    @field:Valid
    val coverUp: CoverUp = CoverUp(),
    @field:Size(max = 20)
    val tagIds: Set<Long> = emptySet(),
)

/** 消费者读取上架罩衫，商城管理员维护全部罩衫目录。 */
@RestController
@RequestMapping
class CoverUpController(
    private val coverUpService: CoverUpService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/cover-ups")
    fun listPublished(): ResponseEntity<ApiResponse> {
        data class Response(val coverUps: List<CoverUpResponse>)
        return builder.ok().data(Response(coverUpService.listPublished().map(CoverUp::toResponse))).build()
    }

    @GetMapping("/api/cover-ups/{id}")
    fun getPublished(@PathVariable id: Long): ResponseEntity<ApiResponse> {
        data class Response(val coverUp: CoverUpResponse)
        val coverUp = coverUpService.getPublished(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(coverUp.toResponse())).build()
    }

    @GetMapping("/api/admin/cover-ups")
    fun listForAdmin(@AuthenticationPrincipal userId: Long): ResponseEntity<ApiResponse> {
        data class Response(val coverUps: List<CoverUpResponse>)
        adminAccessService.requireAdmin(userId)
        return builder.ok().data(Response(coverUpService.listForAdmin().map(CoverUp::toResponse))).build()
    }

    @GetMapping("/api/admin/cover-ups/{id}")
    fun getForAdmin(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val coverUp: CoverUpResponse)
        adminAccessService.requireAdmin(userId)
        val coverUp = coverUpService.getForAdmin(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(coverUp.toResponse())).build()
    }

    @PostMapping("/api/admin/cover-ups")
    fun create(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CoverUpUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val coverUp: CoverUpResponse)
        adminAccessService.requireAdmin(userId)
        val coverUp = coverUpService.create(request.coverUp, request.tagIds)
        return builder.status(HttpStatus.CREATED).data(Response(coverUp.toResponse())).build()
    }

    @PutMapping("/api/admin/cover-ups/{id}")
    fun update(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: CoverUpUpsertRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val coverUp: CoverUpResponse)
        adminAccessService.requireAdmin(userId)
        val coverUp = coverUpService.update(id, request.coverUp, request.tagIds) ?: return builder.notFound().build()
        return builder.ok().data(Response(coverUp.toResponse())).build()
    }

    @DeleteMapping("/api/admin/cover-ups/{id}")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val id: Long, val deleted: Boolean)
        adminAccessService.requireAdmin(userId)
        if (!coverUpService.delete(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }
}
