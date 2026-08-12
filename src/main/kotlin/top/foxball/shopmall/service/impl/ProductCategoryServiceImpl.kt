package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.ProductCategory
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.service.ProductCategoryService

@Service
@Transactional(readOnly = true)
class ProductCategoryServiceImpl(
    private val repository: ProductCategoryRepository,
    private val productRepository: ProductRepository,
) : ProductCategoryService {
    override fun listAll(): List<ProductCategory> = repository.findAllByOrderByDisplayOrderAscNameAsc()

    override fun listActive(): List<ProductCategory> = repository.findAllByStatusOrderByDisplayOrderAscNameAsc(ProductCategory.Status.ACTIVE)

    override fun get(id: Long): ProductCategory? = repository.findById(id).orElse(null)

    @Transactional
    override fun create(
        code: String,
        name: String,
        description: String?,
        parentId: Long?,
        displayOrder: Int,
        status: ProductCategory.Status,
    ): ProductCategory {
        val normalizedCode = normalize(code, name, description, displayOrder)
        if (repository.existsByCode(normalizedCode)) throw ParamErrorException("分类 code 已存在")
        val parent = parentId?.let { repository.findById(it).orElseThrow { ParamErrorException("上级分类不存在") } }
        return repository.saveAndFlush(
            ProductCategory(
                code = normalizedCode,
                name = name.trim(),
                description = description?.trim()?.takeIf(String::isNotEmpty),
                parent = parent,
                displayOrder = displayOrder,
                status = status,
            ),
        )
    }

    @Transactional
    override fun update(
        id: Long,
        code: String,
        name: String,
        description: String?,
        parentId: Long?,
        displayOrder: Int,
        status: ProductCategory.Status,
    ): ProductCategory? {
        val category = repository.findById(id).orElse(null) ?: return null
        if (normalize(code, name, description, displayOrder) != category.code) throw ParamErrorException("分类 code 创建后不能修改")
        if (parentId == id) throw ParamErrorException("分类不能将自身设置为上级分类")
        val parent = parentId?.let { repository.findById(it).orElseThrow { ParamErrorException("上级分类不存在") } }
        var ancestor = parent
        while (ancestor != null) {
            if (ancestor.id == id) throw ParamErrorException("分类层级不能形成循环")
            ancestor = ancestor.parent
        }
        category.name = name.trim()
        category.description = description?.trim()?.takeIf(String::isNotEmpty)
        category.parent = parent
        category.displayOrder = displayOrder
        category.status = status
        return repository.saveAndFlush(category)
    }

    @Transactional
    override fun delete(id: Long): Boolean {
        val category = repository.findById(id).orElse(null) ?: return false
        if (repository.existsByParent_Id(id)) throw ParamErrorException("分类仍有子分类，不能删除")
        if (productRepository.existsByCategory_Id(id)) throw ParamErrorException("分类仍被商品使用，不能删除")
        repository.delete(category)
        repository.flush()
        return true
    }

    private fun normalize(code: String, name: String, description: String?, displayOrder: Int): String {
        val normalizedCode = code.trim().lowercase()
        if (!CODE.matches(normalizedCode)) throw ParamErrorException("分类 code 必须使用小写字母、数字或连字符")
        if (name.trim().isEmpty() || name.trim().length > 100) throw ParamErrorException("分类名称无效")
        if ((description?.trim()?.length ?: 0) > 1_000) throw ParamErrorException("分类说明不能超过 1000 个字符")
        if (displayOrder < 0) throw ParamErrorException("排序值不能小于 0")
        return normalizedCode
    }

    private companion object {
        val CODE = Regex("^[a-z][a-z0-9-]*$")
    }
}
