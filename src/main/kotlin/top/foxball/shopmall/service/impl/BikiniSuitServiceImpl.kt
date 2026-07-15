package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.BikiniSuitRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.BikiniSuitService

@Service
@Transactional(readOnly = true)
class BikiniSuitServiceImpl(
    private val bikiniSuitRepository: BikiniSuitRepository,
    private val tagRepository: TagRepository,
) : BikiniSuitService {
    override fun listPublished(): List<BikiniSuit> =
        bikiniSuitRepository.findAllByStatusOrderByCreatedAtDesc(BikiniSuit.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): BikiniSuit? =
        bikiniSuitRepository.findByIdAndStatus(id, BikiniSuit.Status.ACTIVE)?.let(::hydrate)

    override fun listForAdmin(): List<BikiniSuit> = bikiniSuitRepository.findAll().map(::hydrate)

    override fun getForAdmin(id: Long): BikiniSuit? = bikiniSuitRepository.findById(id).orElse(null)?.let(::hydrate)

    @Transactional
    override fun create(source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit {
        applyTags(source, tagIds)
        return hydrate(bikiniSuitRepository.save(source))
    }

    @Transactional
    override fun update(id: Long, source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit? {
        val target = bikiniSuitRepository.findById(id).orElse(null) ?: return null
        target.applyChangesFrom(source)
        applyTags(target, tagIds)
        return hydrate(target)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val bikiniSuit = bikiniSuitRepository.findById(id).orElse(null) ?: return false
        bikiniSuit.status = BikiniSuit.Status.DELETED
        return true
    }

    private fun BikiniSuit.applyChangesFrom(source: BikiniSuit) {
        name = source.name
        topSize = source.topSize
        bottomSize = source.bottomSize
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
        score = source.score
    }

    private fun <T> MutableList<T>.replaceWith(values: Collection<T>) {
        clear()
        addAll(values)
    }

    private fun applyTags(bikiniSuit: BikiniSuit, tagIds: Collection<Long>) {
        val ids = tagIds.distinct()
        val tagsById = tagRepository.findAllById(ids).associateBy { it.id }
        if (tagsById.size != ids.size) {
            throw ParamErrorException("包含不存在的标签")
        }

        bikiniSuit.tags.clear()
        bikiniSuit.tags.addAll(ids.map(tagsById::get).filterNotNull())
    }

    /** 在事务内初始化前台详情所需的延迟集合，避免控制器序列化时访问已关闭会话。 */
    private fun hydrate(bikiniSuit: BikiniSuit): BikiniSuit = bikiniSuit.apply {
        highlight.size
        images.size
        designAndExtras.size
        careInstructions.size
        tags.size
    }
}
