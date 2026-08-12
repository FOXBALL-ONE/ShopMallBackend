package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.entity.jdbc.ProductVariantAttribute
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.OrderItemRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductVariantRepository
import top.foxball.shopmall.repository.ShoppingCartRepository
import top.foxball.shopmall.service.AttributeValidationService
import top.foxball.shopmall.service.OptionSignatureService
import top.foxball.shopmall.service.ProductVariantInput
import top.foxball.shopmall.service.ProductVariantService
import java.math.BigDecimal

@Service
@Transactional(readOnly = true)
class ProductVariantServiceImpl(
    private val productRepository: ProductRepository,
    private val variantRepository: ProductVariantRepository,
    private val shoppingCartRepository: ShoppingCartRepository,
    private val orderItemRepository: OrderItemRepository,
    private val attributeValidationService: AttributeValidationService,
    private val optionSignatureService: OptionSignatureService,
) : ProductVariantService {
    override fun list(productId: Long): List<ProductVariant> =
        variantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId).onEach { it.attributes.size }

    @Transactional
    override fun create(productId: Long, input: ProductVariantInput): ProductVariant? {
        val product = productRepository.findByIdForUpdate(productId) ?: return null
        if (product.deletedAt != null) throw ParamErrorException("已删除商品不能新增 SKU")
        val normalized = normalize(input, requireNotNull(product.productType?.id))
        if (variantRepository.existsBySku(normalized.sku)) throw ParamErrorException("SKU 已存在")
        if (variantRepository.existsByProduct_IdAndOptionSignature(productId, normalized.optionSignature)) {
            throw ParamErrorException("SKU 规格组合已存在")
        }
        product.addVariant(normalized)
        productRepository.saveAndFlush(product)
        return normalized
    }

    @Transactional
    override fun update(variantId: Long, input: ProductVariantInput): ProductVariant? {
        val product = lockProductForVariant(variantId) ?: return null
        val variant = variantRepository.findDetailedById(variantId) ?: return null
        if (input.sku.trim().uppercase() != variant.sku) throw ParamErrorException("SKU 创建后不能修改")
        if (product.deletedAt != null) throw ParamErrorException("已删除商品不能编辑 SKU")
        val normalized = normalize(input, requireNotNull(product.productType?.id))
        if (normalized.status == ProductVariant.Status.INACTIVE && variant.status == ProductVariant.Status.ACTIVE &&
            product.status == Product.Status.ACTIVE &&
            variantRepository.countByProduct_IdAndStatus(requireNotNull(product.id), ProductVariant.Status.ACTIVE) <= 1
        ) {
            throw ParamErrorException("已上架商品至少需要一个启用 SKU")
        }
        if (variantRepository.existsByProduct_IdAndOptionSignatureAndIdNot(requireNotNull(product.id), normalized.optionSignature, variantId)) {
            throw ParamErrorException("SKU 规格组合已存在")
        }
        variant.size = normalized.size
        variant.color = normalized.color
        variant.price = normalized.price
        variant.status = normalized.status
        variant.displayOrder = normalized.displayOrder
        variant.attributes.clear()
        variant.attributes.addAll(normalized.attributes)
        variant.optionSignature = normalized.optionSignature
        return variantRepository.saveAndFlush(variant)
    }

    @Transactional
    override fun updateStatus(variantId: Long, status: ProductVariant.Status): ProductVariant? {
        val product = lockProductForVariant(variantId) ?: return null
        val variant = variantRepository.findById(variantId).orElse(null) ?: return null
        if (status == ProductVariant.Status.INACTIVE && variant.status != ProductVariant.Status.INACTIVE &&
            variantRepository.countByProduct_IdAndStatus(requireNotNull(product.id), ProductVariant.Status.ACTIVE) <= 1 &&
            product.status == Product.Status.ACTIVE
        ) {
            throw ParamErrorException("已上架商品至少需要一个启用 SKU")
        }
        variant.status = status
        return variantRepository.saveAndFlush(variant)
    }

    @Transactional
    override fun replaceAll(productId: Long, inputs: List<ProductVariantInput>): List<ProductVariant>? {
        val product = productRepository.findByIdForUpdate(productId) ?: return null
        if (product.deletedAt != null) throw ParamErrorException("已删除商品不能编辑 SKU")
        if (inputs.isEmpty()) throw ParamErrorException("商品至少需要一个 SKU")
        val inputIds = inputs.mapNotNull(ProductVariantInput::id)
        if (inputIds.distinct().size != inputIds.size) throw ParamErrorException("SKU id 不能重复")

        val existing = variantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId)
        existing.forEach { it.attributes.size }
        val existingById = existing.associateBy { requireNotNull(it.id) }
        if (inputIds.any { it !in existingById }) throw ParamErrorException("包含不属于当前商品的 SKU")

        val normalized = inputs.map { input -> input to normalize(input, requireNotNull(product.productType?.id)) }
        if (normalized.map { it.second.sku }.distinct().size != normalized.size) throw ParamErrorException("SKU 不能重复")
        if (normalized.map { it.second.optionSignature }.distinct().size != normalized.size) {
            throw ParamErrorException("SKU 规格组合不能重复")
        }
        if (product.status == Product.Status.ACTIVE &&
            normalized.none { it.second.status == ProductVariant.Status.ACTIVE }
        ) {
            throw ParamErrorException("已上架商品至少需要一个启用 SKU")
        }

        normalized.forEach { (input, target) ->
            val current = input.id?.let(existingById::get)
            if (current == null) {
                if (variantRepository.existsBySku(target.sku)) throw ParamErrorException("SKU 已存在: ${target.sku}")
            } else if (current.sku != target.sku) {
                throw ParamErrorException("SKU 创建后不能修改")
            }
        }

        val retainedIds = inputIds.toSet()
        existing.filter { requireNotNull(it.id) !in retainedIds }.forEach { variant ->
            val variantId = requireNotNull(variant.id)
            if (shoppingCartRepository.existsItemByVariantId(variantId)) throw ParamErrorException("SKU 仍在购物车中，只能停用")
            if (orderItemRepository.existsByVariantId(variantId)) throw ParamErrorException("SKU 已被订单使用，只能停用")
            product.removeVariant(variant)
            variantRepository.delete(variant)
        }

        val stockAdjustments = mutableListOf<Pair<Long, Int>>()
        normalized.forEach { (input, target) ->
            val current = input.id?.let(existingById::get)
            if (current == null) {
                product.addVariant(target)
            } else {
                current.size = target.size
                current.color = target.color
                current.price = target.price
                current.status = target.status
                current.displayOrder = target.displayOrder
                current.attributes.clear()
                current.attributes.addAll(target.attributes)
                current.optionSignature = target.optionSignature
                val adjustment = target.warehouseVolume - current.warehouseVolume
                if (adjustment != 0) stockAdjustments += requireNotNull(current.id) to adjustment
            }
        }
        productRepository.saveAndFlush(product)
        stockAdjustments.forEach { (variantId, adjustment) ->
            val changed = if (adjustment > 0) {
                variantRepository.increaseStock(variantId, adjustment, Int.MAX_VALUE - adjustment)
            } else {
                variantRepository.decreaseStock(variantId, -adjustment)
            }
            if (changed == 0) throw ParamErrorException("SKU $variantId 库存不足或已达到上限")
        }
        return variantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId).onEach { it.attributes.size }
    }

    @Transactional
    override fun updateStatuses(variantIds: Collection<Long>, status: ProductVariant.Status): Int {
        val distinctIds = variantIds.distinct()
        if (distinctIds.isEmpty()) return 0
        val productIdsByVariant = distinctIds.associateWith { variantId ->
            variantRepository.findProductIdById(variantId) ?: throw ParamErrorException("包含不存在的 SKU")
        }
        val productIds = productIdsByVariant.values.distinct().sorted()
        val products = productRepository.findAllByIdForUpdate(productIds)
        if (products.size != productIds.size) throw ParamErrorException("包含不存在的商品")
        if (products.any { it.deletedAt != null }) throw ParamErrorException("已删除商品不能编辑 SKU")
        val targetIds = distinctIds.toSet()
        products.forEach { product ->
            val productId = requireNotNull(product.id)
            val variants = variantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId)
            if (product.status == Product.Status.ACTIVE &&
                variants.none { variant ->
                    if (requireNotNull(variant.id) in targetIds) status == ProductVariant.Status.ACTIVE
                    else variant.status == ProductVariant.Status.ACTIVE
                }
            ) {
                throw ParamErrorException("已上架商品至少需要一个启用 SKU")
            }
        }
        val variants = variantRepository.findAllById(distinctIds)
        if (variants.size != distinctIds.size) throw ParamErrorException("包含不存在的 SKU")
        variants.forEach { it.status = status }
        variantRepository.saveAllAndFlush(variants)
        return variants.size
    }

    @Transactional
    override fun delete(variantId: Long): Boolean {
        val product = lockProductForVariant(variantId) ?: return false
        val variant = variantRepository.findDetailedById(variantId) ?: return false
        val productId = requireNotNull(product.id)
        if (variantRepository.findAllByProduct_IdOrderByDisplayOrderAscIdAsc(productId).size <= 1) {
            throw ParamErrorException("商品至少需要保留一个 SKU")
        }
        if (product.status == Product.Status.ACTIVE &&
            variant.status == ProductVariant.Status.ACTIVE &&
            variantRepository.countByProduct_IdAndStatus(productId, ProductVariant.Status.ACTIVE) <= 1
        ) {
            throw ParamErrorException("已上架商品至少需要一个启用 SKU")
        }
        if (shoppingCartRepository.existsItemByVariantId(variantId)) throw ParamErrorException("SKU 仍在购物车中，只能停用")
        if (orderItemRepository.existsByVariantId(variantId)) throw ParamErrorException("SKU 已被订单使用，只能停用")
        variant.product?.removeVariant(variant)
        variantRepository.delete(variant)
        variantRepository.flush()
        return true
    }

    private fun lockProductForVariant(variantId: Long) =
        variantRepository.findProductIdById(variantId)?.let(productRepository::findByIdForUpdate)

    private fun normalize(input: ProductVariantInput, typeId: Long): ProductVariant {
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

    private companion object {
        val SKU = Regex("^[A-Z0-9][A-Z0-9._-]{0,63}$")
    }
}
