package top.foxball.shopmall.service.impl

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.entity.jdbc.ProductAttribute
import top.foxball.shopmall.entity.jdbc.ProductImage
import top.foxball.shopmall.entity.jdbc.ProductVariantAttribute
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.entity.jdbc.ProductType
import top.foxball.shopmall.entity.jdbc.MaterialComponent
import top.foxball.shopmall.entity.jdbc.CareInstruction
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.AttributeValidationService
import top.foxball.shopmall.service.CreateProductCommand
import top.foxball.shopmall.service.OptionSignatureService
import top.foxball.shopmall.service.ProductService
import top.foxball.shopmall.service.ProductVariantInput
import top.foxball.shopmall.service.ProductVariantService
import top.foxball.shopmall.service.UpdateProductCommand
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class ProductServiceImpl(
    private val productRepository: ProductRepository,
    private val productTypeRepository: ProductTypeRepository,
    private val categoryRepository: ProductCategoryRepository,
    private val variantRepository: ProductVariantRepository,
    private val tagRepository: TagRepository,
    private val attributeValidationService: AttributeValidationService,
    private val optionSignatureService: OptionSignatureService,
    private val productVariantService: ProductVariantService,
) : ProductService {
    override fun listPublished(
        productTypeCode: String?,
        categoryId: Long?,
        keyword: String?,
        page: Int,
        size: Int,
    ): Page<Product> {
        if (page < 0 || size !in 1..100) throw ParamErrorException("分页参数无效")
        val normalizedType = productTypeCode?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val normalizedKeyword = keyword?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val specification = Specification<Product> { root, query, criteriaBuilder ->
            val predicates = mutableListOf(
                criteriaBuilder.equal(root.get<Product.Status>("status"), Product.Status.ACTIVE),
                criteriaBuilder.isNull(root.get<LocalDateTime>("deletedAt")),
            )
            if (normalizedType != null) {
                predicates += criteriaBuilder.equal(root.get<ProductType>("productType").get<String>("code"), normalizedType)
            }
            if (categoryId != null) {
                predicates += criteriaBuilder.equal(root.get<ProductCategory>("category").get<Long>("id"), categoryId)
            }
            if (normalizedKeyword != null) {
                predicates += criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), "%$normalizedKeyword%")
            }
            val activeVariant = query.subquery(Long::class.javaObjectType)
            val variantRoot = activeVariant.from(ProductVariant::class.java)
            activeVariant.select(variantRoot.get("id")).where(
                criteriaBuilder.equal(variantRoot.get<Product>("product"), root),
                criteriaBuilder.equal(variantRoot.get<ProductVariant.Status>("status"), ProductVariant.Status.ACTIVE),
            )
            predicates += criteriaBuilder.exists(activeVariant)
            criteriaBuilder.and(*predicates.toTypedArray())
        }
        return productRepository.findAll(
            specification,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        ).also { products ->
            products.content.forEach { product ->
                product.productType?.code
                product.category?.id
                product.highlights.size
                product.materials.size
                product.images.size
                product.attributes.size
                product.tags.size
                product.designAndExtras.size
                product.careInstructions.size
                product.variants.forEach { variant -> variant.attributes.size }
            }
        }
    }

    override fun getPublished(id: Long): Product? =
        productRepository.findDetailedByIdAndStatus(id, Product.Status.ACTIVE)
            ?.takeIf { it.deletedAt == null }
            ?.also(::initialize)

    override fun getAdmin(id: Long): Product? = productRepository.findById(id).orElse(null)?.also { initialize(it) }

    @Transactional
    override fun create(command: CreateProductCommand): Product {
        validateProductFields(
            command.name,
            command.highlights,
            command.materials.map { it.name to it.percentage },
            command.images.map { it.url to it.primary },
            command.fitSense,
            command.description,
            command.designAndExtras,
            command.careInstructions,
            command.tagIds,
        )
        if (command.variants.isEmpty()) throw ParamErrorException("商品至少需要一个 SKU")
        if (command.variants.any { it.id != null }) throw ParamErrorException("新商品 SKU 不能包含 id")
        val productType = productTypeRepository.findById(command.productTypeId).orElseThrow {
            ParamErrorException("商品类型不存在")
        }
        if (!productType.active) throw ParamErrorException("商品类型已停用")
        val category = command.categoryId?.let {
            categoryRepository.findById(it).orElseThrow { ParamErrorException("商品分类不存在") }
        }
        val attributes = attributeValidationService.validateProduct(
            command.productTypeId,
            command.attributes.map { ProductAttribute(it.code, it.value) },
        )
        val tags = resolveTags(command.tagIds)
        val product = Product(
            productType = productType,
            category = category,
            name = command.name.trim(),
            status = command.status,
            highlights = command.highlights.map(String::trim).toMutableList(),
            materials = command.materials.map { MaterialComponent(it.name.trim(), it.percentage) }.toMutableList(),
            attributes = attributes.toMutableList(),
            images = command.images.map { ProductImage(it.url.trim(), it.altText?.trim()?.takeIf(String::isNotEmpty), it.primary) }.toMutableList(),
            fitSense = command.fitSense?.trim()?.takeIf(String::isNotEmpty),
            description = command.description?.trim()?.takeIf(String::isNotEmpty),
            designAndExtras = command.designAndExtras.map(String::trim).toMutableList(),
            careInstructions = command.careInstructions.map { CareInstruction(it.trim()) }.toMutableList(),
            tags = tags.toMutableSet(),
        )
        val normalizedVariants = command.variants.map { normalizeVariantInput(command.productTypeId, it) }
        if (normalizedVariants.map(ProductVariant::sku).distinct().size != normalizedVariants.size) {
            throw ParamErrorException("同一请求中的 SKU 不能重复")
        }
        if (normalizedVariants.map(ProductVariant::optionSignature).distinct().size != normalizedVariants.size) {
            throw ParamErrorException("同一商品中的 SKU 规格不能重复")
        }
        if (command.status == Product.Status.ACTIVE && normalizedVariants.none { it.status == ProductVariant.Status.ACTIVE }) {
            throw ParamErrorException("上架商品至少需要一个启用 SKU")
        }
        normalizedVariants.forEach { variant ->
            if (variantRepository.existsBySku(variant.sku)) throw ParamErrorException("SKU 已存在: ${variant.sku}")
            product.addVariant(variant)
        }
        return productRepository.saveAndFlush(product).also { initialize(it) }
    }

    @Transactional
    override fun update(id: Long, command: UpdateProductCommand): Product? {
        val product = productRepository.findByIdForUpdate(id) ?: return null
        if (product.deletedAt != null) throw ParamErrorException("已删除商品需先恢复")
        if (command.variants.isEmpty()) throw ParamErrorException("商品至少需要一个 SKU")
        if (command.status == Product.Status.ACTIVE && command.variants.none { it.status == ProductVariant.Status.ACTIVE }) {
            throw ParamErrorException("上架商品至少需要一个启用 SKU")
        }
        validateProductFields(
            command.name,
            command.highlights,
            command.materials.map { it.name to it.percentage },
            command.images.map { it.url to it.primary },
            command.fitSense,
            command.description,
            command.designAndExtras,
            command.careInstructions,
            command.tagIds,
        )
        val typeId = requireNotNull(product.productType?.id)
        val attributes = attributeValidationService.validateProduct(
            typeId,
            command.attributes.map { ProductAttribute(it.code, it.value) },
        )
        product.category = command.categoryId?.let {
            categoryRepository.findById(it).orElseThrow { ParamErrorException("商品分类不存在") }
        }
        product.name = command.name.trim()
        product.status = command.status
        product.highlights.clear()
        product.highlights.addAll(command.highlights.map(String::trim))
        product.materials.clear()
        product.materials.addAll(command.materials.map { MaterialComponent(it.name.trim(), it.percentage) })
        product.attributes.clear()
        product.attributes.addAll(attributes)
        product.images.clear()
        product.images.addAll(command.images.map { ProductImage(it.url.trim(), it.altText?.trim()?.takeIf(String::isNotEmpty), it.primary) })
        product.fitSense = command.fitSense?.trim()?.takeIf(String::isNotEmpty)
        product.description = command.description?.trim()?.takeIf(String::isNotEmpty)
        product.designAndExtras.clear()
        product.designAndExtras.addAll(command.designAndExtras.map(String::trim))
        product.careInstructions.clear()
        product.careInstructions.addAll(command.careInstructions.map { CareInstruction(it.trim()) })
        product.tags.clear()
        product.tags.addAll(resolveTags(command.tagIds))
        productRepository.saveAndFlush(product)
        productVariantService.replaceAll(id, command.variants)
            ?: throw IllegalStateException("商品在聚合更新过程中不存在")
        return productRepository.findById(id).orElseThrow().also { initialize(it) }
    }

    private fun normalizeVariantInput(typeId: Long, input: ProductVariantInput): ProductVariant {
        val sku = input.sku.trim().uppercase()
        if (!SKU.matches(sku)) throw ParamErrorException("SKU 格式无效")
        val size = input.size?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val color = input.color.trim()
        if (color.isEmpty() || color.length > 50) throw ParamErrorException("SKU 颜色无效")
        if (input.price <= BigDecimal.ZERO || input.price.scale() > 2 || input.price.precision() - input.price.scale() > 8) {
            throw ParamErrorException("SKU 价格必须是大于 0 的 USD 金额且最多两位小数")
        }
        if (input.warehouseVolume < 0 || input.displayOrder < 0) throw ParamErrorException("SKU 库存和排序值不能小于 0")
        val attributes = attributeValidationService.validateVariant(
            typeId,
            input.attributes.map { ProductVariantAttribute(it.code, it.value) },
        )
        return ProductVariant(
            sku = sku,
            size = size,
            color = color,
            price = input.price.setScale(2),
            warehouseVolume = input.warehouseVolume,
            displayOrder = input.displayOrder,
            status = input.status,
            attributes = attributes.toMutableList(),
            optionSignature = optionSignatureService.generate(size, color, attributes),
        )
    }

    private fun validateProductFields(
        name: String,
        highlights: List<String>,
        materials: List<Pair<String, BigDecimal>>,
        images: List<Pair<String, Boolean>>,
        fitSense: String?,
        description: String?,
        designAndExtras: List<String>,
        careInstructions: List<String>,
        tagIds: Set<Long>,
    ) {
        if (name.trim().isEmpty() || name.trim().length > 200) throw ParamErrorException("商品名称无效")
        validateTextList(highlights, 10, "商品卖点")
        validateTextList(designAndExtras, 12, "设计细节")
        validateTextList(careInstructions, 12, "洗护说明")
        if ((fitSense?.trim()?.length ?: 0) > 255) throw ParamErrorException("版型说明不能超过 255 个字符")
        if ((description?.trim()?.length ?: 0) > 4_000) throw ParamErrorException("商品描述不能超过 4000 个字符")
        if (materials.size > 10 || materials.any { it.first.trim().isEmpty() || it.first.trim().length > 100 || it.second <= BigDecimal.ZERO }) {
            throw ParamErrorException("面料信息无效")
        }
        if (materials.isNotEmpty() && materials.fold(BigDecimal.ZERO) { total, item -> total + item.second }.compareTo(BigDecimal("100.00")) != 0) {
            throw ParamErrorException("面料占比合计必须为 100%")
        }
        if (images.size > 12 || images.any { it.first.isBlank() || it.first.length > 512 }) throw ParamErrorException("商品图片信息无效")
        if (images.isNotEmpty() && images.count { it.second } != 1) throw ParamErrorException("有商品图片时必须且只能指定一张主图")
        if (tagIds.size > 20 || tagIds.any { it <= 0 }) throw ParamErrorException("商品标签无效")
    }

    private fun validateTextList(values: List<String>, maxSize: Int, label: String) {
        if (values.size > maxSize || values.any { it.trim().isEmpty() || it.trim().length > 255 }) {
            throw ParamErrorException("$label 无效")
        }
    }

    private fun resolveTags(tagIds: Set<Long>) = tagRepository.findAllById(tagIds).also { tags ->
        if (tags.size != tagIds.size) throw ParamErrorException("包含不存在的标签")
    }

    private fun initialize(product: Product) {
        product.productType?.code
        product.category?.id
        product.highlights.size
        product.materials.size
        product.attributes.size
        product.images.size
        product.designAndExtras.size
        product.careInstructions.size
        product.tags.size
        product.variants.forEach { it.attributes.size }
    }

    private companion object {
        val SKU = Regex("^[A-Z0-9][A-Z0-9._-]{0,63}$")
    }
}
