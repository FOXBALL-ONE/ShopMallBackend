package top.foxball.shopmall.service.impl

import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.TagRepository

/**
 * 各品类子类服务共享的基类字段编辑与延迟集合初始化逻辑。
 *
 * [applyBaseChangesFrom] 仅复制商品资料字段；库存、销量和状态通过独立管理操作维护，score 由评价派生，
 * id/createdAt/updatedAt 由 JPA 维护。
 */
internal fun Product.applyBaseChangesFrom(source: Product) {
    name = source.name
    color = source.color
    price = source.price
    highlight.replaceWith(source.highlight)
    images.replaceWith(source.images)
    fitSense = source.fitSense
    description = source.description
    designAndExtras.replaceWith(source.designAndExtras)
    careInstructions.replaceWith(source.careInstructions)
}

/** 已删除商品只能通过显式恢复流程重新进入可管理状态。 */
internal fun Product.requireNotDeletedForUpdate() {
    if (status == Product.Status.DELETED) {
        throw ParamErrorException("已删除商品需先恢复")
    }
}

/** 新商品不能直接进入已删除状态，累计销量始终从零开始并由订单流程维护。 */
internal fun Product.prepareForCreate() {
    if (status == Product.Status.DELETED) {
        throw ParamErrorException("新商品不能使用已删除状态")
    }
    salesVolume = 0
}

/** 按标签 ID 集合校验存在性并整体替换商品的标签关联；标签由标签目录独立维护。 */
internal fun Product.applyTags(tagRepository: TagRepository, tagIds: Collection<Long>) {
    val ids = tagIds.distinct()
    val tagsById = tagRepository.findAllById(ids).associateBy { it.id }
    if (tagsById.size != ids.size) {
        throw ParamErrorException("包含不存在的标签")
    }
    val assignedTagIds = tags.mapNotNull { it.id }.toSet()
    if (tagsById.values.any { !it.active && it.id !in assignedTagIds }) {
        throw ParamErrorException("停用标签不能分配给商品")
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
