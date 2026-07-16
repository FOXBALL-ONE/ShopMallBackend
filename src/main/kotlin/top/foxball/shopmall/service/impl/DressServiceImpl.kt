package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.repository.DressRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.DressService

@Service
@Transactional(readOnly = true)
class DressServiceImpl(
    private val dressRepository: DressRepository,
    private val tagRepository: TagRepository,
) : DressService {
    override fun listPublished(): List<Dress> =
        dressRepository.findAllByStatusOrderByCreatedAtDesc(Product.Status.ACTIVE).map(::hydrate)

    override fun getPublished(id: Long): Dress? =
        dressRepository.findByIdAndStatus(id, Product.Status.ACTIVE)?.let(::hydrate)

    override fun listForAdmin(): List<Dress> = dressRepository.findAll().map(::hydrate)

    override fun getForAdmin(id: Long): Dress? = dressRepository.findById(id).orElse(null)?.let(::hydrate)

    @Transactional
    override fun create(source: Dress, tagIds: Collection<Long>): Dress {
        source.applyTags(tagRepository, tagIds)
        return hydrate(dressRepository.save(source))
    }

    @Transactional
    override fun update(id: Long, source: Dress, tagIds: Collection<Long>): Dress? {
        val target = dressRepository.findById(id).orElse(null) ?: return null
        target.applyChangesFrom(source)
        target.applyTags(tagRepository, tagIds)
        return hydrate(target)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val dress = dressRepository.findById(id).orElse(null) ?: return false
        dress.status = Product.Status.DELETED
        return true
    }

    private fun Dress.applyChangesFrom(source: Dress) {
        size = source.size
        length = source.length
        silhouette = source.silhouette
        neckline = source.neckline
        sleeveType = source.sleeveType
        fabric = source.fabric
        applyBaseChangesFrom(source)
    }

    private fun hydrate(dress: Dress): Dress = dress.apply { hydrateBase() }
}
