package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.CoverUp

/**
 * 罩衫商品服务，区分前台上架数据与后台完整数据。
 *
 * 写操作通过 [tagIds] 绑定商品标签；删除为软删除，以保留评价和未来的订单关联。
 */
interface CoverUpService {
    /** 按创建时间倒序返回所有已上架罩衫。 */
    fun listPublished(): List<CoverUp>

    /** 查询一件已上架罩衫；不存在或未上架时返回 `null`。 */
    fun getPublished(id: Long): CoverUp?

    /** 返回后台可管理的全部罩衫，包括已下架的。 */
    fun listForAdmin(): List<CoverUp>

    /** 查询后台可见的指定罩衫；不存在时返回 `null`。 */
    fun getForAdmin(id: Long): CoverUp?

    /** 创建一件罩衫并绑定指定的标签。 */
    fun create(source: CoverUp, tagIds: Collection<Long>): CoverUp

    /** 更新罩衫的字段与标签；目标不存在时返回 `null`。 */
    fun update(id: Long, source: CoverUp, tagIds: Collection<Long>): CoverUp?

    /** 下架商品而非物理删除，以保留评价和未来的订单关联。 */
    fun delete(id: Long): Boolean
}
