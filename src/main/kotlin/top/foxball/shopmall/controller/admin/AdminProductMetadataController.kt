package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.AttributeScope
import top.foxball.shopmall.entity.jdbc.AttributeValueType
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AttributeDefinitionMutation
import top.foxball.shopmall.service.ProductCategoryService
import top.foxball.shopmall.service.ProductTypeMutation
import top.foxball.shopmall.service.ProductTypeService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder

@Validated
@RestController
@RequestMapping("/admin/api")
class AdminProductMetadataController(
    private val productTypeService: ProductTypeService,
    private val productCategoryService: ProductCategoryService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/product-types")
    fun listTypes(@AuthenticationPrincipal adminId: Long): ResponseEntity<Response> {
        data class TypeData(
            val id: Long,
            val code: String,
            val name: String,
            val description: String?,
            val active: Boolean,
            @param:JsonProperty("display_order") val displayOrder: Int,
        )
        data class Response(val list: List<TypeData>)

        adminAccessService.requireAdmin(adminId)
        val rs = Response(
            productTypeService.listAll().map {
                TypeData(requireNotNull(it.id), it.code, it.name, it.description, it.active, it.displayOrder)
            },
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/product-types")
    fun createType(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("code") @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") @Size(max = 64) code: String,
        @RequestParam("name") @Size(min = 1, max = 100) name: String,
        @RequestParam("description", required = false) @Size(max = 1_000) description: String?,
        @RequestParam("active", defaultValue = "true") active: Boolean,
        @RequestParam("display_order", defaultValue = "0") @Min(0) @Max(1_000_000) displayOrder: Int,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val code: String)

        adminAccessService.requireAdmin(adminId)
        val type = productTypeService.create(ProductTypeMutation(code, name, description, active, displayOrder))
        return builder.created().data(Response(requireNotNull(type.id), type.code)).build()
    }

    @PutMapping("/product-types/{id}")
    fun updateType(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
        @RequestParam("code") @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") @Size(max = 64) code: String,
        @RequestParam("name") @Size(min = 1, max = 100) name: String,
        @RequestParam("description", required = false) @Size(max = 1_000) description: String?,
        @RequestParam("active") active: Boolean,
        @RequestParam("display_order") @Min(0) @Max(1_000_000) displayOrder: Int,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val active: Boolean)

        adminAccessService.requireAdmin(adminId)
        val type = productTypeService.update(id, ProductTypeMutation(code, name, description, active, displayOrder))
            ?: return builder.notFound().build()
        return builder.ok().data(Response(requireNotNull(type.id), type.active)).build()
    }

    @DeleteMapping("/product-types/{id}")
    fun deleteType(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!productTypeService.delete(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }

    @GetMapping("/product-types/{type_id}/attributes")
    fun listDefinitions(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("type_id") @Min(1) typeId: Long,
    ): ResponseEntity<Response> {
        data class AttributeData(
            val id: Long,
            val code: String,
            val name: String,
            val scope: String,
            @param:JsonProperty("value_type") val valueType: String,
            val required: Boolean,
            val filterable: Boolean,
            @param:JsonProperty("allowed_values") val allowedValues: List<String>,
            @param:JsonProperty("max_length") val maxLength: Int?,
            @param:JsonProperty("display_order") val displayOrder: Int,
            val active: Boolean,
            val used: Boolean,
        )
        data class Response(val list: List<AttributeData>)

        adminAccessService.requireAdmin(adminId)
        if (productTypeService.getById(typeId) == null) return builder.notFound().build()
        val rs = Response(
            productTypeService.listDefinitions(typeId).map {
                AttributeData(
                    requireNotNull(it.id),
                    it.code,
                    it.name,
                    it.scope.name,
                    it.valueType.name,
                    it.required,
                    it.filterable,
                    it.allowedValues.toList(),
                    it.maxLength,
                    it.displayOrder,
                    it.active,
                    productTypeService.isDefinitionUsed(requireNotNull(it.id)),
                )
            },
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/product-types/{type_id}/attributes")
    fun createDefinition(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("type_id") @Min(1) typeId: Long,
        @RequestParam("code") @Pattern(regexp = "^[a-z][a-z0-9_]*$") @Size(max = 64) code: String,
        @RequestParam("name") @Size(min = 1, max = 100) name: String,
        @RequestParam("scope") scope: AttributeScope,
        @RequestParam("value_type") valueType: AttributeValueType,
        @RequestParam("required", defaultValue = "false") required: Boolean,
        @RequestParam("filterable", defaultValue = "false") filterable: Boolean,
        @RequestParam("allowed_values", required = false) @Size(max = 100) allowedValues: List<String>?,
        @RequestParam("max_length", required = false) @Min(1) @Max(1_000) maxLength: Int?,
        @RequestParam("display_order", defaultValue = "0") @Min(0) @Max(1_000_000) displayOrder: Int,
        @RequestParam("active", defaultValue = "true") active: Boolean,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val code: String)

        adminAccessService.requireAdmin(adminId)
        val definition = productTypeService.createDefinition(
            typeId,
            AttributeDefinitionMutation(code, name, scope, valueType, required, filterable, allowedValues.orEmpty(), maxLength, displayOrder, active),
        ) ?: return builder.notFound().build()
        return builder.created().data(Response(requireNotNull(definition.id), definition.code)).build()
    }

    @PutMapping("/attribute-definitions/{id}")
    fun updateDefinition(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
        @RequestParam("code") @Pattern(regexp = "^[a-z][a-z0-9_]*$") @Size(max = 64) code: String,
        @RequestParam("name") @Size(min = 1, max = 100) name: String,
        @RequestParam("scope") scope: AttributeScope,
        @RequestParam("value_type") valueType: AttributeValueType,
        @RequestParam("required") required: Boolean,
        @RequestParam("filterable") filterable: Boolean,
        @RequestParam("allowed_values", required = false) @Size(max = 100) allowedValues: List<String>?,
        @RequestParam("max_length", required = false) @Min(1) @Max(1_000) maxLength: Int?,
        @RequestParam("display_order") @Min(0) @Max(1_000_000) displayOrder: Int,
        @RequestParam("active") active: Boolean,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val active: Boolean)

        adminAccessService.requireAdmin(adminId)
        val definition = productTypeService.updateDefinition(
            id,
            AttributeDefinitionMutation(code, name, scope, valueType, required, filterable, allowedValues.orEmpty(), maxLength, displayOrder, active),
        ) ?: return builder.notFound().build()
        return builder.ok().data(Response(requireNotNull(definition.id), definition.active)).build()
    }

    @DeleteMapping("/attribute-definitions/{id}")
    fun deleteDefinition(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!productTypeService.deleteDefinition(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }

    @GetMapping("/product-categories")
    fun listCategories(@AuthenticationPrincipal adminId: Long): ResponseEntity<Response> {
        data class CategoryData(
            val id: Long,
            val code: String,
            val name: String,
            val description: String?,
            @param:JsonProperty("parent_id") val parentId: Long?,
            @param:JsonProperty("display_order") val displayOrder: Int,
            val status: String,
        )
        data class Response(val list: List<CategoryData>)

        adminAccessService.requireAdmin(adminId)
        val rs = Response(
            productCategoryService.listAll().map {
                CategoryData(requireNotNull(it.id), it.code, it.name, it.description, it.parent?.id, it.displayOrder, it.status.name)
            },
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping("/product-categories")
    fun createCategory(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("code") @Pattern(regexp = "^[a-z][a-z0-9-]*$") @Size(max = 64) code: String,
        @RequestParam("name") @Size(min = 1, max = 100) name: String,
        @RequestParam("description", required = false) @Size(max = 1_000) description: String?,
        @RequestParam("parent_id", required = false) @Min(1) parentId: Long?,
        @RequestParam("display_order", defaultValue = "0") @Min(0) @Max(1_000_000) displayOrder: Int,
        @RequestParam("status", defaultValue = "ACTIVE") status: ProductCategory.Status,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val code: String)

        adminAccessService.requireAdmin(adminId)
        val category = productCategoryService.create(code, name, description, parentId, displayOrder, status)
        return builder.created().data(Response(requireNotNull(category.id), category.code)).build()
    }

    @PutMapping("/product-categories/{id}")
    fun updateCategory(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
        @RequestParam("code") @Pattern(regexp = "^[a-z][a-z0-9-]*$") @Size(max = 64) code: String,
        @RequestParam("name") @Size(min = 1, max = 100) name: String,
        @RequestParam("description", required = false) @Size(max = 1_000) description: String?,
        @RequestParam("parent_id", required = false) @Min(1) parentId: Long?,
        @RequestParam("display_order") @Min(0) @Max(1_000_000) displayOrder: Int,
        @RequestParam("status") status: ProductCategory.Status,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val status: String)

        adminAccessService.requireAdmin(adminId)
        val category = productCategoryService.update(id, code, name, description, parentId, displayOrder, status)
            ?: return builder.notFound().build()
        return builder.ok().data(Response(requireNotNull(category.id), category.status.name)).build()
    }

    @DeleteMapping("/product-categories/{id}")
    fun deleteCategory(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("id") @Min(1) id: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        adminAccessService.requireAdmin(adminId)
        if (!productCategoryService.delete(id)) return builder.notFound().build()
        return builder.ok().data(Response(id, true)).build()
    }
}
