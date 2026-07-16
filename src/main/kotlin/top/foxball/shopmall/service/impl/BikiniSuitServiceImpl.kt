package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.Product
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
        bikiniSuitRepository.findAllByStatusOrderByCreatedAtDesc(Product.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): BikiniSuit? =
        bikiniSuitRepository.findByIdAndStatus(id, Product.Status.ACTIVE)?.let(::hydrate)

    override fun listForAdmin(): List<BikiniSuit> = bikiniSuitRepository.findAll().map(::hydrate)

    override fun getForAdmin(id: Long): BikiniSuit? = bikiniSuitRepository.findById(id).orElse(null)?.let(::hydrate)

    @Transactional
    override fun create(source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit {
        source.applyTags(tagRepository, tagIds)
        return hydrate(bikiniSuitRepository.save(source))
    }

    @Transactional
    override fun update(id: Long, source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit? {
        val target = bikiniSuitRepository.findById(id).orElse(null) ?: return null
        target.applyChangesFrom(source)
        target.applyTags(tagRepository, tagIds)
        return hydrate(target)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val bikiniSuit = bikiniSuitRepository.findById(id).orElse(null) ?: return false
        bikiniSuit.status = Product.Status.DELETED
        return true
    }

    private fun BikiniSuit.applyChangesFrom(source: BikiniSuit) {
        topSize = source.topSize
        bottomSize = source.bottomSize
        applyBaseChangesFrom(source)
    }

    /** 在事务内初始化前台详情所需的延迟集合，避免控制器序列化时访问已关闭会话。 */
    private fun hydrate(bikiniSuit: BikiniSuit): BikiniSuit = bikiniSuit.apply { hydrateBase() }
}
