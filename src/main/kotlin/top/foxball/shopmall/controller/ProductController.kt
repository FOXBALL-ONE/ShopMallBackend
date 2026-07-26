package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.SizeRecommendation
import top.foxball.shopmall.service.ProductService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * @folder 商品
 */
@RestController
class ProductController(
    private val productService: ProductService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 获取已上架商品列表
     */
    @GetMapping("/api/products")
    fun getPublishedProducts(): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
        )

        data class ProductData(
            val id: Long,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            @param:JsonProperty("sales_volume")
            val salesVolume: Int,
            val status: String,
            val highlight: List<String>,
            val images: List<String>,
            @param:JsonProperty("fit_sense")
            val fitSense: String?,
            val description: String?,
            @param:JsonProperty("design_and_extras")
            val designAndExtras: List<String>,
            @param:JsonProperty("care_instructions")
            val careInstructions: List<String>,
            val score: Float?,
            val tags: List<TagData>,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
            @param:JsonProperty("top_size")
            val topSize: String? = null,
            @param:JsonProperty("top_size_recommendation")
            val topSizeRecommendation: SizeRecommendation? = null,
            @param:JsonProperty("bottom_size")
            val bottomSize: String? = null,
            @param:JsonProperty("bottom_size_recommendation")
            val bottomSizeRecommendation: SizeRecommendation? = null,
            val size: String? = null,
            @param:JsonProperty("size_recommendation")
            val sizeRecommendation: SizeRecommendation? = null,
            @param:JsonProperty("support_level")
            val supportLevel: String? = null,
            val coverage: String? = null,
            @param:JsonProperty("torso_fit")
            val torsoFit: String? = null,
            val neckline: String? = null,
            @param:JsonProperty("back_style")
            val backStyle: String? = null,
            @param:JsonProperty("tummy_control")
            val tummyControl: Boolean? = null,
            @param:JsonProperty("removable_padding")
            val removablePadding: Boolean? = null,
            val length: String? = null,
            val silhouette: String? = null,
            @param:JsonProperty("sleeve_type")
            val sleeveType: String? = null,
            val fabric: String? = null,
            val style: String? = null,
            @param:JsonProperty("sheer_level")
            val sheerLevel: String? = null,
        )

        data class Response(val list: List<ProductData>)

        val list = productService.listPublished().map { product ->
            val tags = product.tags.sortedBy { it.sortOrder }.map {
                TagData(requireNotNull(it.id), it.name, it.description, it.color, it.sortOrder)
            }
            when (product) {
                is BikiniSuit -> ProductData(
                    id = requireNotNull(product.id),
                    productType = "BIKINI",
                    name = product.name,
                    color = product.color,
                    price = product.price,
                    warehouseVolume = product.warehouseVolume,
                    salesVolume = product.salesVolume,
                    status = product.status.name,
                    highlight = product.highlight.toList(),
                    images = product.images.toList(),
                    fitSense = product.fitSense,
                    description = product.description,
                    designAndExtras = product.designAndExtras.toList(),
                    careInstructions = product.careInstructions.toList(),
                    score = product.score,
                    tags = tags,
                    createdAt = product.createdAt,
                    updatedAt = product.updatedAt,
                    topSize = product.topSize?.name,
                    topSizeRecommendation = product.topSize?.recommendation,
                    bottomSize = product.bottomSize?.name,
                    bottomSizeRecommendation = product.bottomSize?.recommendation,
                )

                is OnePieceSuit -> {
                    val size = requireNotNull(product.size)
                    ProductData(
                        id = requireNotNull(product.id),
                        productType = "ONE_PIECE",
                        name = product.name,
                        color = product.color,
                        price = product.price,
                        warehouseVolume = product.warehouseVolume,
                        salesVolume = product.salesVolume,
                        status = product.status.name,
                        highlight = product.highlight.toList(),
                        images = product.images.toList(),
                        fitSense = product.fitSense,
                        description = product.description,
                        designAndExtras = product.designAndExtras.toList(),
                        careInstructions = product.careInstructions.toList(),
                        score = product.score,
                        tags = tags,
                        createdAt = product.createdAt,
                        updatedAt = product.updatedAt,
                        size = size.name,
                        sizeRecommendation = size.recommendation,
                        supportLevel = product.supportLevel?.name,
                        coverage = product.coverage?.name,
                        torsoFit = product.torsoFit?.name,
                        neckline = product.neckline?.name,
                        backStyle = product.backStyle?.name,
                        tummyControl = product.tummyControl,
                        removablePadding = product.removablePadding,
                    )
                }

                is Dress -> {
                    val size = requireNotNull(product.size)
                    ProductData(
                        id = requireNotNull(product.id),
                        productType = "DRESS",
                        name = product.name,
                        color = product.color,
                        price = product.price,
                        warehouseVolume = product.warehouseVolume,
                        salesVolume = product.salesVolume,
                        status = product.status.name,
                        highlight = product.highlight.toList(),
                        images = product.images.toList(),
                        fitSense = product.fitSense,
                        description = product.description,
                        designAndExtras = product.designAndExtras.toList(),
                        careInstructions = product.careInstructions.toList(),
                        score = product.score,
                        tags = tags,
                        createdAt = product.createdAt,
                        updatedAt = product.updatedAt,
                        size = size.name,
                        sizeRecommendation = size.recommendation,
                        length = product.length?.name,
                        silhouette = product.silhouette?.name,
                        neckline = product.neckline?.name,
                        sleeveType = product.sleeveType?.name,
                        fabric = product.fabric,
                    )
                }

                is CoverUp -> ProductData(
                    id = requireNotNull(product.id),
                    productType = "COVER_UP",
                    name = product.name,
                    color = product.color,
                    price = product.price,
                    warehouseVolume = product.warehouseVolume,
                    salesVolume = product.salesVolume,
                    status = product.status.name,
                    highlight = product.highlight.toList(),
                    images = product.images.toList(),
                    fitSense = product.fitSense,
                    description = product.description,
                    designAndExtras = product.designAndExtras.toList(),
                    careInstructions = product.careInstructions.toList(),
                    score = product.score,
                    tags = tags,
                    createdAt = product.createdAt,
                    updatedAt = product.updatedAt,
                    size = product.size.name,
                    style = product.style?.name,
                    sheerLevel = product.sheerLevel?.name,
                    fabric = product.fabric,
                )

                else -> error("不支持的商品类型: ${product::class.simpleName}")
            }
        }
        val rs = Response(list)
        return builder.ok().data(rs).build()
    }

    /**
     * @api 获取已上架商品
     * @param id 商品 ID
     */
    @GetMapping("/api/products/{id}")
    fun getPublishedProduct(
        @PathVariable("id") id: Long,
    ): ResponseEntity<Response> {
        data class TagData(
            val id: Long,
            val name: String,
            val description: String?,
            val color: String?,
            @param:JsonProperty("sort_order")
            val sortOrder: Int,
        )

        data class ProductData(
            val id: Long,
            @param:JsonProperty("product_type")
            val productType: String,
            val name: String,
            val color: String,
            val price: BigDecimal,
            @param:JsonProperty("warehouse_volume")
            val warehouseVolume: Int,
            @param:JsonProperty("sales_volume")
            val salesVolume: Int,
            val status: String,
            val highlight: List<String>,
            val images: List<String>,
            @param:JsonProperty("fit_sense")
            val fitSense: String?,
            val description: String?,
            @param:JsonProperty("design_and_extras")
            val designAndExtras: List<String>,
            @param:JsonProperty("care_instructions")
            val careInstructions: List<String>,
            val score: Float?,
            val tags: List<TagData>,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at")
            val updatedAt: LocalDateTime?,
            @param:JsonProperty("top_size")
            val topSize: String? = null,
            @param:JsonProperty("top_size_recommendation")
            val topSizeRecommendation: SizeRecommendation? = null,
            @param:JsonProperty("bottom_size")
            val bottomSize: String? = null,
            @param:JsonProperty("bottom_size_recommendation")
            val bottomSizeRecommendation: SizeRecommendation? = null,
            val size: String? = null,
            @param:JsonProperty("size_recommendation")
            val sizeRecommendation: SizeRecommendation? = null,
            @param:JsonProperty("support_level")
            val supportLevel: String? = null,
            val coverage: String? = null,
            @param:JsonProperty("torso_fit")
            val torsoFit: String? = null,
            val neckline: String? = null,
            @param:JsonProperty("back_style")
            val backStyle: String? = null,
            @param:JsonProperty("tummy_control")
            val tummyControl: Boolean? = null,
            @param:JsonProperty("removable_padding")
            val removablePadding: Boolean? = null,
            val length: String? = null,
            val silhouette: String? = null,
            @param:JsonProperty("sleeve_type")
            val sleeveType: String? = null,
            val fabric: String? = null,
            val style: String? = null,
            @param:JsonProperty("sheer_level")
            val sheerLevel: String? = null,
        )

        data class Response(val product: ProductData)

        val product = productService.getPublished(id) ?: return builder.notFound().build()
        val tags = product.tags.sortedBy { it.sortOrder }.map {
            TagData(requireNotNull(it.id), it.name, it.description, it.color, it.sortOrder)
        }
        val productData = when (product) {
            is BikiniSuit -> ProductData(
                id = requireNotNull(product.id),
                productType = "BIKINI",
                name = product.name,
                color = product.color,
                price = product.price,
                warehouseVolume = product.warehouseVolume,
                salesVolume = product.salesVolume,
                status = product.status.name,
                highlight = product.highlight.toList(),
                images = product.images.toList(),
                fitSense = product.fitSense,
                description = product.description,
                designAndExtras = product.designAndExtras.toList(),
                careInstructions = product.careInstructions.toList(),
                score = product.score,
                tags = tags,
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
                topSize = product.topSize?.name,
                topSizeRecommendation = product.topSize?.recommendation,
                bottomSize = product.bottomSize?.name,
                bottomSizeRecommendation = product.bottomSize?.recommendation,
            )

            is OnePieceSuit -> {
                val size = requireNotNull(product.size)
                ProductData(
                    id = requireNotNull(product.id),
                    productType = "ONE_PIECE",
                    name = product.name,
                    color = product.color,
                    price = product.price,
                    warehouseVolume = product.warehouseVolume,
                    salesVolume = product.salesVolume,
                    status = product.status.name,
                    highlight = product.highlight.toList(),
                    images = product.images.toList(),
                    fitSense = product.fitSense,
                    description = product.description,
                    designAndExtras = product.designAndExtras.toList(),
                    careInstructions = product.careInstructions.toList(),
                    score = product.score,
                    tags = tags,
                    createdAt = product.createdAt,
                    updatedAt = product.updatedAt,
                    size = size.name,
                    sizeRecommendation = size.recommendation,
                    supportLevel = product.supportLevel?.name,
                    coverage = product.coverage?.name,
                    torsoFit = product.torsoFit?.name,
                    neckline = product.neckline?.name,
                    backStyle = product.backStyle?.name,
                    tummyControl = product.tummyControl,
                    removablePadding = product.removablePadding,
                )
            }

            is Dress -> {
                val size = requireNotNull(product.size)
                ProductData(
                    id = requireNotNull(product.id),
                    productType = "DRESS",
                    name = product.name,
                    color = product.color,
                    price = product.price,
                    warehouseVolume = product.warehouseVolume,
                    salesVolume = product.salesVolume,
                    status = product.status.name,
                    highlight = product.highlight.toList(),
                    images = product.images.toList(),
                    fitSense = product.fitSense,
                    description = product.description,
                    designAndExtras = product.designAndExtras.toList(),
                    careInstructions = product.careInstructions.toList(),
                    score = product.score,
                    tags = tags,
                    createdAt = product.createdAt,
                    updatedAt = product.updatedAt,
                    size = size.name,
                    sizeRecommendation = size.recommendation,
                    length = product.length?.name,
                    silhouette = product.silhouette?.name,
                    neckline = product.neckline?.name,
                    sleeveType = product.sleeveType?.name,
                    fabric = product.fabric,
                )
            }

            is CoverUp -> ProductData(
                id = requireNotNull(product.id),
                productType = "COVER_UP",
                name = product.name,
                color = product.color,
                price = product.price,
                warehouseVolume = product.warehouseVolume,
                salesVolume = product.salesVolume,
                status = product.status.name,
                highlight = product.highlight.toList(),
                images = product.images.toList(),
                fitSense = product.fitSense,
                description = product.description,
                designAndExtras = product.designAndExtras.toList(),
                careInstructions = product.careInstructions.toList(),
                score = product.score,
                tags = tags,
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
                size = product.size.name,
                style = product.style?.name,
                sheerLevel = product.sheerLevel?.name,
                fabric = product.fabric,
            )

            else -> error("不支持的商品类型: ${product::class.simpleName}")
        }
        val rs = Response(productData)
        return builder.ok().data(rs).build()
    }
}
