package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.service.ProductCategoryService
import top.foxball.shopmall.service.ProductTypeService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder

@RestController
@RequestMapping("/api")
class ProductMetadataController(
    private val productTypeService: ProductTypeService,
    private val productCategoryService: ProductCategoryService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/product-types")
    fun listTypes(): ResponseEntity<Response> {
        data class TypeData(val id: Long, val code: String, val name: String, val description: String?, @param:JsonProperty("display_order") val displayOrder: Int)
        data class Response(val list: List<TypeData>)
        return builder.ok().data(Response(productTypeService.listActive().map { TypeData(requireNotNull(it.id), it.code, it.name, it.description, it.displayOrder) })).build()
    }

    @GetMapping("/product-types/{id}/attributes")
    fun listAttributes(@PathVariable("id") id: Long): ResponseEntity<Response> {
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
        )
        data class Response(val list: List<AttributeData>)
        val list = productTypeService.listDefinitions(id).filter { it.active }.map { definition ->
            AttributeData(requireNotNull(definition.id), definition.code, definition.name, definition.scope.name, definition.valueType.name, definition.required, definition.filterable, definition.allowedValues.toList(), definition.maxLength, definition.displayOrder, definition.active)
        }
        return builder.ok().data(Response(list)).build()
    }

    @GetMapping("/product-categories")
    fun listCategories(): ResponseEntity<Response> {
        data class CategoryData(val id: Long, val code: String, val name: String, @param:JsonProperty("parent_id") val parentId: Long?, @param:JsonProperty("display_order") val displayOrder: Int, val status: String)
        data class Response(val list: List<CategoryData>)
        val list = productCategoryService.listActive().map { category -> CategoryData(requireNotNull(category.id), category.code, category.name, category.parent?.id, category.displayOrder, category.status.name) }
        return builder.ok().data(Response(list)).build()
    }
}
