package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.repository.CoverUpRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.CoverUpService

@Service
@Transactional(readOnly = true)
class CoverUpServiceImpl(
    private val coverUpRepository: CoverUpRepository,
    private val tagRepository: TagRepository,
) : CoverUpService {
    override fun listPublished(): List<CoverUp> =
        coverUpRepository.findAllByStatusOrderByCreatedAtDesc(Product.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): CoverUp? =
        coverUpRepository.findByIdAndStatus(id, Product.Status.ACTIVE)?.let(::hydrate)

    override fun listForAdmin(): List<CoverUp> = coverUpRepository.findAll().map(::hydrate)

    override fun getForAdmin(id: Long): CoverUp? = coverUpRepository.findById(id).orElse(null)?.let(::hydrate)

    @Transactional
    override fun create(source: CoverUp, tagIds: Collection<Long>): CoverUp {
        source.applyTags(tagRepository, tagIds)
        return hydrate(coverUpRepository.save(source))
    }

    @Transactional
    override fun update(id: Long, source: CoverUp, tagIds: Collection<Long>): CoverUp? {
        val target = coverUpRepository.findById(id).orElse(null) ?: return null
        target.applyChangesFrom(source)
        target.applyTags(tagRepository, tagIds)
        return hydrate(target)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val coverUp = coverUpRepository.findById(id).orElse(null) ?: return false
        coverUp.status = Product.Status.DELETED
        return true
    }

    private fun CoverUp.applyChangesFrom(source: CoverUp) {
        style = source.style
        sheerLevel = source.sheerLevel
        fabric = source.fabric
        size = source.size
        applyBaseChangesFrom(source)
    }

    private fun hydrate(coverUp: CoverUp): CoverUp = coverUp.apply { hydrateBase() }
}
