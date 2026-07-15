package top.foxball.shopmall.controller

import jakarta.validation.Valid
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
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.TagService
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.shared.Response as ApiResponse

/** 提供前台标签读取和管理员标签 CRUD 接口。 */
@RestController
@RequestMapping
class TagController(
    private val tagService: TagService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/tags")
    fun listPublished(): ResponseEntity<ApiResponse> {
        data class Response(val tags: List<Tag>)
        return builder.ok().data(Response(tagService.listPublished())).build()
    }

    @GetMapping("/api/tags/{id}")
    fun getPublished(@PathVariable id: Long): ResponseEntity<ApiResponse> {
        data class Response(val tag: Tag)
        val tag = tagService.getPublished(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(tag)).build()
    }

    @GetMapping("/api/admin/tags")
    fun listForAdmin(@AuthenticationPrincipal userId: Long): ResponseEntity<ApiResponse> {
        data class Response(val tags: List<Tag>)
        adminAccessService.requireAdmin(userId)
        return builder.ok().data(Response(tagService.listForAdmin())).build()
    }

    @GetMapping("/api/admin/tags/{id}")
    fun getForAdmin(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val tag: Tag)
        adminAccessService.requireAdmin(userId)
        val tag = tagService.getForAdmin(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(tag)).build()
    }

    @PostMapping("/api/admin/tags")
    fun create(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody tag: Tag,
    ): ResponseEntity<ApiResponse> {
        data class Response(val tag: Tag)
        adminAccessService.requireAdmin(userId)
        return builder.status(HttpStatus.CREATED).data(Response(tagService.create(tag))).build()
    }

    @PutMapping("/api/admin/tags/{id}")
    fun update(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody tag: Tag,
    ): ResponseEntity<ApiResponse> {
        data class Response(val tag: Tag)
        adminAccessService.requireAdmin(userId)
        val updatedTag = tagService.update(id, tag) ?: return builder.notFound().build()
        return builder.ok().data(Response(updatedTag)).build()
    }

    @DeleteMapping("/api/admin/tags/{id}")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse> {
        data class Response(val id: Long, val deleted: Boolean)
        adminAccessService.requireAdmin(userId)
        if (!tagService.delete(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }
}
