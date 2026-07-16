package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.SizeRecommendation
import top.foxball.shopmall.entity.jdbc.Tag
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 商品响应的多态根。
 *
 * Jackson 按子类型写入 productType 鉴别字段，前台聚合接口 GET /api/products 可在同一个数组中
 * 返回各品类商品；各品类管理端接口复用对应的子类型响应，保证字段结构一致。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "productType")
@JsonSubTypes(
    JsonSubTypes.Type(BikiniSuitResponse::class, name = "BIKINI"),
    JsonSubTypes.Type(OnePieceSuitResponse::class, name = "ONE_PIECE"),
    JsonSubTypes.Type(DressResponse::class, name = "DRESS"),
    JsonSubTypes.Type(CoverUpResponse::class, name = "COVER_UP"),
)
sealed interface ProductResponse

data class BikiniSuitResponse(
    val id: Long,
    val name: String,
    val topSize: BikiniSuit.Size?,
    val topSizeRecommendation: SizeRecommendation?,
    val bottomSize: BikiniSuit.Size?,
    val bottomSizeRecommendation: SizeRecommendation?,
    val color: String,
    val price: BigDecimal,
    val warehouseVolume: Int,
    val salesVolume: Int,
    val status: Product.Status,
    val highlight: List<String>,
    val images: List<String>,
    val fitSense: String?,
    val description: String?,
    val designAndExtras: List<String>,
    val careInstructions: List<String>,
    val score: Float?,
    val tags: List<Tag>,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
) : ProductResponse

fun BikiniSuit.toResponse(): BikiniSuitResponse = BikiniSuitResponse(
    id = requireNotNull(id),
    name = name,
    topSize = topSize,
    topSizeRecommendation = topSize?.recommendation,
    bottomSize = bottomSize,
    bottomSizeRecommendation = bottomSize?.recommendation,
    color = color,
    price = price,
    warehouseVolume = warehouseVolume,
    salesVolume = salesVolume,
    status = status,
    highlight = highlight.toList(),
    images = images.toList(),
    fitSense = fitSense,
    description = description,
    designAndExtras = designAndExtras.toList(),
    careInstructions = careInstructions.toList(),
    score = score,
    tags = tags.sortedBy(Tag::sortOrder),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

data class OnePieceSuitResponse(
    val id: Long,
    val name: String,
    val size: OnePieceSuit.Size,
    val sizeRecommendation: SizeRecommendation?,
    val color: String,
    val price: BigDecimal,
    val warehouseVolume: Int,
    val salesVolume: Int,
    val supportLevel: OnePieceSuit.SupportLevel?,
    val coverage: OnePieceSuit.Coverage?,
    val torsoFit: OnePieceSuit.TorsoFit?,
    val neckline: OnePieceSuit.Neckline?,
    val backStyle: OnePieceSuit.BackStyle?,
    val tummyControl: Boolean,
    val removablePadding: Boolean,
    val status: Product.Status,
    val highlight: List<String>,
    val images: List<String>,
    val fitSense: String?,
    val description: String?,
    val designAndExtras: List<String>,
    val careInstructions: List<String>,
    val score: Float?,
    val tags: List<Tag>,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
) : ProductResponse

fun OnePieceSuit.toResponse(): OnePieceSuitResponse {
    val requiredSize = requireNotNull(size)
    return OnePieceSuitResponse(
        id = requireNotNull(id),
        name = name,
        size = requiredSize,
        sizeRecommendation = requiredSize.recommendation,
        color = color,
        price = price,
        warehouseVolume = warehouseVolume,
        salesVolume = salesVolume,
        supportLevel = supportLevel,
        coverage = coverage,
        torsoFit = torsoFit,
        neckline = neckline,
        backStyle = backStyle,
        tummyControl = tummyControl,
        removablePadding = removablePadding,
        status = status,
        highlight = highlight.toList(),
        images = images.toList(),
        fitSense = fitSense,
        description = description,
        designAndExtras = designAndExtras.toList(),
        careInstructions = careInstructions.toList(),
        score = score,
        tags = tags.sortedBy(Tag::sortOrder),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

data class DressResponse(
    val id: Long,
    val name: String,
    val size: Dress.Size,
    val sizeRecommendation: SizeRecommendation?,
    val length: Dress.Length?,
    val silhouette: Dress.Silhouette?,
    val neckline: Dress.Neckline?,
    val sleeveType: Dress.SleeveType?,
    val fabric: String?,
    val color: String,
    val price: BigDecimal,
    val warehouseVolume: Int,
    val salesVolume: Int,
    val status: Product.Status,
    val highlight: List<String>,
    val images: List<String>,
    val fitSense: String?,
    val description: String?,
    val designAndExtras: List<String>,
    val careInstructions: List<String>,
    val score: Float?,
    val tags: List<Tag>,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
) : ProductResponse

fun Dress.toResponse(): DressResponse {
    val requiredSize = requireNotNull(size)
    return DressResponse(
        id = requireNotNull(id),
        name = name,
        size = requiredSize,
        sizeRecommendation = requiredSize.recommendation,
        length = length,
        silhouette = silhouette,
        neckline = neckline,
        sleeveType = sleeveType,
        fabric = fabric,
        color = color,
        price = price,
        warehouseVolume = warehouseVolume,
        salesVolume = salesVolume,
        status = status,
        highlight = highlight.toList(),
        images = images.toList(),
        fitSense = fitSense,
        description = description,
        designAndExtras = designAndExtras.toList(),
        careInstructions = careInstructions.toList(),
        score = score,
        tags = tags.sortedBy(Tag::sortOrder),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

data class CoverUpResponse(
    val id: Long,
    val name: String,
    val style: CoverUp.CoverUpStyle?,
    val sheerLevel: CoverUp.SheerLevel?,
    val fabric: String?,
    val size: CoverUp.Size,
    val color: String,
    val price: BigDecimal,
    val warehouseVolume: Int,
    val salesVolume: Int,
    val status: Product.Status,
    val highlight: List<String>,
    val images: List<String>,
    val fitSense: String?,
    val description: String?,
    val designAndExtras: List<String>,
    val careInstructions: List<String>,
    val score: Float?,
    val tags: List<Tag>,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
) : ProductResponse

fun CoverUp.toResponse(): CoverUpResponse = CoverUpResponse(
    id = requireNotNull(id),
    name = name,
    style = style,
    sheerLevel = sheerLevel,
    fabric = fabric,
    size = size,
    color = color,
    price = price,
    warehouseVolume = warehouseVolume,
    salesVolume = salesVolume,
    status = status,
    highlight = highlight.toList(),
    images = images.toList(),
    fitSense = fitSense,
    description = description,
    designAndExtras = designAndExtras.toList(),
    careInstructions = careInstructions.toList(),
    score = score,
    tags = tags.sortedBy(Tag::sortOrder),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** 多态分发：按商品运行时类型转换为对应的响应体。新增品类时在此追加分支。 */
fun Product.toResponse(): ProductResponse = when (this) {
    is BikiniSuit -> toResponse()
    is OnePieceSuit -> toResponse()
    is Dress -> toResponse()
    is CoverUp -> toResponse()
    else -> error("不支持的商品类型：${this::class.simpleName}")
}
