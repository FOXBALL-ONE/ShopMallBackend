package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.TagService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/**
 * @folder 商品/标签
 */
@Validated
@RestController
class TagController(
    private val tagService: TagService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取可用标签列表
     */
    @GetMapping("/api/tags")
    fun getPublishedTags(): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
            val active: Boolean,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<TagData>)

        val list = tagService.listPublished().map {
            TagData(
                id = requireNotNull(it.id),
                name = it.name,
                description = it.description,
                color = it.color,
                sortOrder = it.sortOrder,
                active = it.active,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取可用标签
     * @param id 标签 ID
     */
    @GetMapping("/api/tags/{id}")
    fun getPublishedTag(
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
            val active: Boolean,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        val tag = tagService.getPublished(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(tag.id),
            name = tag.name,
            description = tag.description,
            color = tag.color,
            sortOrder = tag.sortOrder,
            active = tag.active,
            createdAt = tag.createdAt,
            updatedAt = tag.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端标签列表
     */
    @GetMapping("/api/admin/tags")
    fun getAdminTags(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
            val active: Boolean,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        data class Response(val list: List<TagData>)

        adminAccessService.requireAdmin(adminId)
        val list = tagService.listForAdmin().map {
            TagData(
                id = requireNotNull(it.id),
                name = it.name,
                description = it.description,
                color = it.color,
                sortOrder = it.sortOrder,
                active = it.active,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取管理端标签
     * @param id 标签 ID
     */
    @GetMapping("/api/admin/tags/{id}")
    fun getAdminTag(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
            val active: Boolean,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val tag = tagService.getForAdmin(id) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(tag.id),
            name = tag.name,
            description = tag.description,
            color = tag.color,
            sortOrder = tag.sortOrder,
            active = tag.active,
            createdAt = tag.createdAt,
            updatedAt = tag.updatedAt,
        )
        return builder.ok().data(rs).build()
    }

    /**
     * @api 创建标签
     * @param name 标签名称
     * @param description 标签说明
     * @param color 标签颜色
     * @param sortOrder 排序值
     * @param active 是否启用
     */
    @PostMapping("/api/admin/tags")
    fun createTag(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("name") @NotBlank @Size(max = 64) name: String,
        @RequestParam("description", required = false) @Size(max = 255) description: String?,
        @RequestParam("color", required = false) @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") color: String?,
        @RequestParam("sort_order", defaultValue = "0") @Min(0) sortOrder: Int,
        @RequestParam("active", defaultValue = "true") active: Boolean,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val active: Boolean,
        )

        adminAccessService.requireAdmin(adminId)
        val tag = tagService.create(
            Tag(name = name, description = description, color = color, sortOrder = sortOrder, active = active),
        )
        val rs = Response(requireNotNull(tag.id), tag.name, tag.active)
        return builder.status(HttpStatus.CREATED).data(rs).build()
    }

    /**
     * @api 更新标签
     * @param id 标签 ID
     * @param name 标签名称
     * @param description 标签说明
     * @param color 标签颜色
     * @param sortOrder 排序值
     * @param active 是否启用
     */
    @PutMapping("/api/admin/tags/{id}")
    fun updateTag(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
        @RequestParam("name") @NotBlank @Size(max = 64) name: String,
        @RequestParam("description", required = false) @Size(max = 255) description: String?,
        @RequestParam("color", required = false) @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") color: String?,
        @RequestParam("sort_order") @Min(0) sortOrder: Int,
        @RequestParam("active") active: Boolean,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val active: Boolean,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
        )

        adminAccessService.requireAdmin(adminId)
        val tag = tagService.update(
            id,
            Tag(name = name, description = description, color = color, sortOrder = sortOrder, active = active),
        ) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(tag.id), tag.name, tag.active, tag.updatedAt)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 删除标签
     * @param id 标签 ID
     */
    @DeleteMapping("/api/admin/tags/{id}")
    fun deleteTag(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!tagService.delete(id)) return builder.notFound().build()
        val rs = Response(id, true)
        return builder.ok().data(rs).build()
    }
}
