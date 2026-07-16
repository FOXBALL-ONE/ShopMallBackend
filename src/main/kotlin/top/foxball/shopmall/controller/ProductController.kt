package top.foxball.shopmall.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.service.ProductService
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.shared.Response as ApiResponse

/** 跨品类商品聚合读取接口：前台可在同一列表中浏览全部上架商品。 */
@RestController
@RequestMapping
class ProductController(
    private val productService: ProductService,
    private val builder: ResponseBuilder,
) {
    @GetMapping("/api/products")
    fun listPublished(): ResponseEntity<ApiResponse> {
        data class Response(val products: List<ProductResponse>)
        return builder.ok().data(Response(productService.listPublished().map(Product::toResponse))).build()
    }

    @GetMapping("/api/products/{id}")
    fun getPublished(@PathVariable id: Long): ResponseEntity<ApiResponse> {
        data class Response(val product: ProductResponse)
        val product = productService.getPublished(id) ?: return builder.notFound().build()
        return builder.ok().data(Response(product.toResponse())).build()
    }
}
