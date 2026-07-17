package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.repository.DressRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.DressService

/**
 * [DressService] 的实现：类级只读事务，写方法另开读写事务。
 * 复用 [applyBaseChangesFrom] 合并公共字段、[applyTags] 替换标签关联，返回前 [hydrate] 初始化延迟集合。
 */
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

    /** 合并连衣裙版型与面料相关的可编辑字段，随后委托 [applyBaseChangesFrom] 处理公共字段。 */
    private fun Dress.applyChangesFrom(source: Dress) {
        size = source.size
        length = source.length
        silhouette = source.silhouette
        neckline = source.neckline
        sleeveType = source.sleeveType
        fabric = source.fabric
        applyBaseChangesFrom(source)
    }

    /** 在事务内初始化前台详情所需的延迟集合，避免控制器序列化时访问已关闭会话。 */
    private fun hydrate(dress: Dress): Dress = dress.apply { hydrateBase() }
}
