package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.repository.OnePieceSuitRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.OnePieceSuitService

/**
 * [OnePieceSuitService] 的实现：类级只读事务，写方法另开读写事务。
 * 复用 [applyBaseChangesFrom] 合并公共字段、[applyTags] 替换标签关联，返回前 [hydrate] 初始化延迟集合。
 */
@Service
@Transactional(readOnly = true)
class OnePieceSuitServiceImpl(
    private val onePieceSuitRepository: OnePieceSuitRepository,
    private val tagRepository: TagRepository,
) : OnePieceSuitService {
    override fun listPublished(): List<OnePieceSuit> =
        onePieceSuitRepository.findAllByStatusOrderByCreatedAtDesc(Product.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): OnePieceSuit? =
        onePieceSuitRepository.findByIdAndStatus(id, Product.Status.ACTIVE)?.let(::hydrate)

    override fun listForAdmin(): List<OnePieceSuit> = onePieceSuitRepository.findAll().map(::hydrate)

    override fun getForAdmin(id: Long): OnePieceSuit? =
        onePieceSuitRepository.findById(id).orElse(null)?.let(::hydrate)

    @Transactional
    override fun create(source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit {
        source.prepareForCreate()
        source.applyTags(tagRepository, tagIds)
        return hydrate(onePieceSuitRepository.save(source))
    }

    @Transactional
    override fun update(id: Long, source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit? {
        val target = onePieceSuitRepository.findById(id).orElse(null) ?: return null
        target.requireNotDeletedForUpdate()
        target.applyChangesFrom(source)
        target.applyTags(tagRepository, tagIds)
        return hydrate(target)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val onePieceSuit = onePieceSuitRepository.findById(id).orElse(null) ?: return false
        onePieceSuit.status = Product.Status.DELETED
        return true
    }

    /** 合并一件式泳衣版型与结构相关的可编辑字段，随后委托 [applyBaseChangesFrom] 处理公共字段。 */
    private fun OnePieceSuit.applyChangesFrom(source: OnePieceSuit) {
        size = source.size
        supportLevel = source.supportLevel
        coverage = source.coverage
        torsoFit = source.torsoFit
        neckline = source.neckline
        backStyle = source.backStyle
        tummyControl = source.tummyControl
        removablePadding = source.removablePadding
        applyBaseChangesFrom(source)
    }

    /** 在事务内初始化详情响应所需集合，避免控制器访问关闭后的持久化会话。 */
    private fun hydrate(onePieceSuit: OnePieceSuit): OnePieceSuit = onePieceSuit.apply { hydrateBase() }
}
