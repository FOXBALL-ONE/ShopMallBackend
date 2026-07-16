package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.OnePieceSuitRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.OnePieceSuitService

@Service
@Transactional(readOnly = true)
class OnePieceSuitServiceImpl(
    private val onePieceSuitRepository: OnePieceSuitRepository,
    private val tagRepository: TagRepository,
) : OnePieceSuitService {
    override fun listPublished(): List<OnePieceSuit> =
        onePieceSuitRepository.findAllByStatusOrderByCreatedAtDesc(OnePieceSuit.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): OnePieceSuit? =
        onePieceSuitRepository.findByIdAndStatus(id, OnePieceSuit.Status.ACTIVE)?.let(::hydrate)

    override fun listForAdmin(): List<OnePieceSuit> = onePieceSuitRepository.findAll().map(::hydrate)

    override fun getForAdmin(id: Long): OnePieceSuit? =
        onePieceSuitRepository.findById(id).orElse(null)?.let(::hydrate)

    @Transactional
    override fun create(source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit {
        applyTags(source, tagIds)
        return hydrate(onePieceSuitRepository.save(source))
    }

    @Transactional
    override fun update(id: Long, source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit? {
        val target = onePieceSuitRepository.findById(id).orElse(null) ?: return null
        target.applyChangesFrom(source)
        applyTags(target, tagIds)
        return hydrate(target)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val onePieceSuit = onePieceSuitRepository.findById(id).orElse(null) ?: return false
        onePieceSuit.status = OnePieceSuit.Status.DELETED
        return true
    }

    private fun OnePieceSuit.applyChangesFrom(source: OnePieceSuit) {
        name = source.name
        size = source.size
        color = source.color
        price = source.price
        warehouseVolume = source.warehouseVolume
        salesVolume = source.salesVolume
        supportLevel = source.supportLevel
        coverage = source.coverage
        torsoFit = source.torsoFit
        neckline = source.neckline
        backStyle = source.backStyle
        tummyControl = source.tummyControl
        removablePadding = source.removablePadding
        status = source.status
        highlight.replaceWith(source.highlight)
        images.replaceWith(source.images)
        fitSense = source.fitSense
        description = source.description
        designAndExtras.replaceWith(source.designAndExtras)
        careInstructions.replaceWith(source.careInstructions)
        score = source.score
    }

    private fun <T> MutableList<T>.replaceWith(values: Collection<T>) {
        clear()
        addAll(values)
    }

    private fun applyTags(onePieceSuit: OnePieceSuit, tagIds: Collection<Long>) {
        val ids = tagIds.distinct()
        val tagsById = tagRepository.findAllById(ids).associateBy { it.id }
        if (tagsById.size != ids.size) {
            throw ParamErrorException("包含不存在的标签")
        }

        onePieceSuit.tags.clear()
        onePieceSuit.tags.addAll(ids.map(tagsById::get).filterNotNull())
    }

    /** 在事务内初始化详情响应所需集合，避免控制器访问关闭后的持久化会话。 */
    private fun hydrate(onePieceSuit: OnePieceSuit): OnePieceSuit = onePieceSuit.apply {
        highlight.size
        images.size
        designAndExtras.size
        careInstructions.size
        tags.size
    }
}
