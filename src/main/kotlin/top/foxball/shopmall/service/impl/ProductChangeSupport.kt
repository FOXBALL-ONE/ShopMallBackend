package top.foxball.shopmall.service.impl

import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.TagRepository

/**
 * 各品类子类服务共享的基类字段编辑与延迟集合初始化逻辑。
 *
 * [applyBaseChangesFrom] 仅复制管理员可编辑的公共字段；score 为由评价派生的计算字段，不由管理员编辑，
 * id/createdAt/updatedAt 为不可变字段，由 JPA 维护。
 */
internal fun Product.applyBaseChangesFrom(source: Product) {
    name = source.name
    color = source.color
    price = source.price
    warehouseVolume = source.warehouseVolume
    salesVolume = source.salesVolume
    status = source.status
    highlight.replaceWith(source.highlight)
    images.replaceWith(source.images)
    fitSense = source.fitSense
    description = source.description
    designAndExtras.replaceWith(source.designAndExtras)
    careInstructions.replaceWith(source.careInstructions)
}

/** 按标签 ID 集合校验存在性并整体替换商品的标签关联；标签由标签目录独立维护。 */
internal fun Product.applyTags(tagRepository: TagRepository, tagIds: Collection<Long>) {
    val ids = tagIds.distinct()
    val tagsById = tagRepository.findAllById(ids).associateBy { it.id }
    if (tagsById.size != ids.size) {
        throw ParamErrorException("包含不存在的标签")
    }
    tags.clear()
    tags.addAll(ids.map(tagsById::get).filterNotNull())
}

internal fun <T> MutableList<T>.replaceWith(values: Collection<T>) {
    clear()
    addAll(values)
}

/** 在事务内初始化前台详情响应所需的公共延迟集合，避免控制器序列化时访问已关闭会话。 */
internal fun Product.hydrateBase() {
    highlight.size
    images.size
    designAndExtras.size
    careInstructions.size
    tags.size
}
