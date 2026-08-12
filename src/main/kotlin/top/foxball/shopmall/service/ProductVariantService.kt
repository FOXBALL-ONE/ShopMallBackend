package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.ProductVariant

interface ProductVariantService {
    fun list(productId: Long): List<ProductVariant>
    fun create(productId: Long, input: ProductVariantInput): ProductVariant?
    fun update(variantId: Long, input: ProductVariantInput): ProductVariant?
    fun updateStatus(variantId: Long, status: ProductVariant.Status): ProductVariant?
    fun replaceAll(productId: Long, inputs: List<ProductVariantInput>): List<ProductVariant>?
    fun updateStatuses(variantIds: Collection<Long>, status: ProductVariant.Status): Int
    fun delete(variantId: Long): Boolean
}
