package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.Tag

/** 商品标签目录服务，区分前台可用数据与后台完整数据。 */
interface TagService {
    /** 返回所有启用标签，并按配置的展示顺序排列。 */
    fun listPublished(): List<Tag>

    /** 查询启用的标签；不存在或已停用时返回 `null`。 */
    fun getPublished(id: Long): Tag?

    /** 返回后台可管理的全部标签，包括已停用标签。 */
    fun listForAdmin(): List<Tag>

    /** 查询后台可见的指定标签；不存在时返回 `null`。 */
    fun getForAdmin(id: Long): Tag?

    /** 创建标签；标签名称必须唯一。 */
    fun create(source: Tag): Tag

    /** 更新标签；目标不存在时返回 `null`。 */
    fun update(id: Long, source: Tag): Tag?

    /** 删除未被商品使用的标签；目标不存在时返回 `false`。 */
    fun delete(id: Long): Boolean
}
