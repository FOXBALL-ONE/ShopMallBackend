package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.BikiniSuitRepository
import top.foxball.shopmall.repository.OnePieceSuitRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.TagService

@Service
@Transactional(readOnly = true)
class TagServiceImpl(
    private val tagRepository: TagRepository,
    private val bikiniSuitRepository: BikiniSuitRepository,
    private val onePieceSuitRepository: OnePieceSuitRepository,
) : TagService {
    override fun listPublished(): List<Tag> = tagRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc()

    override fun getPublished(id: Long): Tag? = tagRepository.findByIdAndActiveTrue(id)

    override fun listForAdmin(): List<Tag> = tagRepository.findAll()

    override fun getForAdmin(id: Long): Tag? = tagRepository.findById(id).orElse(null)

    @Transactional
    override fun create(source: Tag): Tag {
        source.name = source.name.trim()
        if (tagRepository.existsByName(source.name)) {
            throw ParamErrorException("标签名称已存在")
        }
        return tagRepository.save(source)
    }

    @Transactional
    override fun update(id: Long, source: Tag): Tag? {
        val target = tagRepository.findById(id).orElse(null) ?: return null
        val name = source.name.trim()
        if (tagRepository.existsByNameAndIdNot(name, id)) {
            throw ParamErrorException("标签名称已存在")
        }
        target.name = name
        target.description = source.description
        target.color = source.color
        target.sortOrder = source.sortOrder
        target.active = source.active
        return target
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val tag = tagRepository.findById(id).orElse(null) ?: return false
        if (bikiniSuitRepository.existsByTags_Id(id) || onePieceSuitRepository.existsByTags_Id(id)) {
            throw ParamErrorException("标签仍被商品使用，不能删除")
        }
        tagRepository.delete(tag)
        return true
    }
}
